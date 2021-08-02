/**
 * Copyright (c) 2013-2021 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection;

import io.netty.util.concurrent.ScheduledFuture;
import org.redisson.api.RFuture;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.config.*;
import org.redisson.connection.ClientConnectionsEntry.FreezeReason;
import org.redisson.misc.RedisURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ConnectionManager} for AWS ElastiCache Replication Groups or Azure Redis Cache. By providing all nodes
 * of the replication group to this manager, the role of each node can be polled to determine
 * if a failover has occurred resulting in a new master.
 *
 * @author Nikita Koksharov
 * @author Steve Ungerer
 */
public class ReplicatedConnectionManager extends MasterSlaveConnectionManager {

    private static final String ROLE_KEY = "role";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final AtomicReference<InetSocketAddress> currentMaster = new AtomicReference<>();

    private ScheduledFuture<?> monitorFuture;

    private enum Role {
        master,
        slave
    }

    public ReplicatedConnectionManager(ReplicatedServersConfig cfg, Config config, UUID id) {
        super(config, id);

        this.config = create(cfg);
        initTimer(this.config);

        for (String address : cfg.getNodeAddresses()) {
            RedisURI addr = new RedisURI(address);
            RFuture<RedisConnection> connectionFuture = connectToNode(cfg, addr, addr.getHost());
            connectionFuture.awaitUninterruptibly();
            RedisConnection connection = connectionFuture.getNow();
            if (connection == null) {
                continue;
            }

            Role role = Role.valueOf(connection.sync(RedisCommands.INFO_REPLICATION).get(ROLE_KEY));
            if (Role.master.equals(role)) {
                currentMaster.set(connection.getRedisClient().getAddr());
                log.info("{} is the master", addr);
                this.config.setMasterAddress(addr.toString());
            } else {
                log.info("{} is a slave", addr);
                this.config.addSlaveAddress(addr.toString());
            }
        }

        if (currentMaster.get() == null) {
            stopThreads();
            throw new RedisConnectionException("Can't connect to servers!");
        }
        if (this.config.getReadMode() != ReadMode.MASTER && this.config.getSlaveAddresses().isEmpty()) {
            log.warn("ReadMode = " + this.config.getReadMode() + ", but slave nodes are not found! Please specify all nodes in replicated mode.");
        }

        initSingleEntry();

        scheduleMasterChangeCheck(cfg);
    }

    @Override
    protected MasterSlaveServersConfig create(BaseMasterSlaveServersConfig<?> cfg) {
        MasterSlaveServersConfig res = super.create(cfg);
        res.setDatabase(((ReplicatedServersConfig) cfg).getDatabase());
        return res;
    }
    
    private void scheduleMasterChangeCheck(ReplicatedServersConfig cfg) {
        if (isShuttingDown()) {
            return;
        }
        
        monitorFuture = group.schedule(new Runnable() {
            @Override
            public void run() {
                if (isShuttingDown()) {
                    return;
                }

                InetSocketAddress master = currentMaster.get();
                log.debug("Current master: {}", master);
                
                AtomicInteger count = new AtomicInteger(cfg.getNodeAddresses().size());
                for (String address : cfg.getNodeAddresses()) {
                    RedisURI uri = new RedisURI(address);
                    RFuture<RedisConnection> connectionFuture = connectToNode(cfg, uri, uri.getHost());
                    connectionFuture.onComplete((connection, exc) -> {
                        if (exc != null) {
                            log.error(exc.getMessage(), exc);
                            if (count.decrementAndGet() == 0) {
                                scheduleMasterChangeCheck(cfg);
                            }
                            return;
                        }
                        
                        if (isShuttingDown()) {
                            return;
                        }
                        
                        RFuture<Map<String, String>> result = connection.async(RedisCommands.INFO_REPLICATION);
                        result.onComplete((r, ex) -> {
                            if (ex != null) {
                                log.error(ex.getMessage(), ex);
                                closeNodeConnection(connection);
                                if (count.decrementAndGet() == 0) {
                                    scheduleMasterChangeCheck(cfg);
                                }
                                return;
                            }

                            InetSocketAddress addr = connection.getRedisClient().getAddr();
                            Role role = Role.valueOf(r.get(ROLE_KEY));
                            if (Role.master.equals(role)) {
                                if (master.equals(addr)) {
                                    log.debug("Current master {} unchanged", master);
                                } else if (currentMaster.compareAndSet(master, addr)) {
                                    RFuture<RedisClient> changeFuture = changeMaster(singleSlotRange.getStartSlot(), uri);
                                    changeFuture.onComplete((res, e) -> {
                                        if (e != null) {
                                            currentMaster.compareAndSet(addr, master);
                                        }
                                    });
                                }
                            } else if (!config.checkSkipSlavesInit()) {
                                slaveUp(uri, addr);
                            }
                            
                            if (count.decrementAndGet() == 0) {
                                scheduleMasterChangeCheck(cfg);
                            }
                        });
                    });
                }
            }

        }, cfg.getScanInterval(), TimeUnit.MILLISECONDS);
    }

    private void slaveUp(RedisURI uri, InetSocketAddress address) {
        MasterSlaveEntry entry = getEntry(singleSlotRange.getStartSlot());
        if (entry.slaveUp(address, FreezeReason.MANAGER)) {
            log.info("slave: {} is up", uri);
        }
    }
    
    @Override
    public void shutdown() {
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
        }
        
        closeNodeConnections();
        super.shutdown();
    }
}

