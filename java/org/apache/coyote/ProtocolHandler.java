/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.tomcat.util.net.SSLHostConfig;

/**
 * Abstract the protocol implementation, including threading, etc.
 *
 * This is the main interface to be implemented by a coyote protocol.
 * Adapter is the main interface to be implemented by a coyote servlet
 * container.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 * @see Adapter
 */
public interface ProtocolHandler {

    /**
     * Return the adapter associated with the protocol handler.
     * @return the adapter
     * Adapter 的功能 是Request/Response 与 ServletRequest/ServletResponse 的相互转化
     */
    public Adapter getAdapter();


    /**
     * The adapter, used to call the connector.
     *
     * @param adapter The adapter to associate
     */
    public void setAdapter(Adapter adapter);


    /**
     * The executor, provide access to the underlying thread pool.
     *
     * @return The executor used to process requests
     */
    public Executor getExecutor();


    /**
     * Set the optional executor that will be used by the connector.
     * @param executor the executor
     */
    public void setExecutor(Executor executor);


    /**
     * Get the utility executor that should be used by the protocol handler.
     * @return the executor
     */
    public ScheduledExecutorService getUtilityExecutor();


    /**
     * Set the utility executor that should be used by the protocol handler.
     * @param utilityExecutor the executor
     */
    public void setUtilityExecutor(ScheduledExecutorService utilityExecutor);


    /**
     * Initialise the protocol.
     *
     * @throws Exception If the protocol handler fails to initialise
     *
     * 初始化协议
     */
    public void init() throws Exception;


    /**
     * Start the protocol.
     *
     * @throws Exception If the protocol handler fails to start
     */
    public void start() throws Exception;


    /**
     * Pause the protocol (optional).
     *
     * @throws Exception If the protocol handler fails to pause
     */
    public void pause() throws Exception;


    /**
     * Resume the protocol (optional).
     *
     * @throws Exception If the protocol handler fails to resume
     */
    public void resume() throws Exception;


    /**
     * Stop the protocol.
     *
     * @throws Exception If the protocol handler fails to stop
     */
    public void stop() throws Exception;


    /**
     * Destroy the protocol (optional).
     *
     * @throws Exception If the protocol handler fails to destroy
     */
    public void destroy() throws Exception;


    /**
     * Close the server socket (to prevent further connections) if the server
     * socket was bound on {@link #start()} (rather than on {@link #init()}
     * but do not perform any further shutdown.
     */
    public void closeServerSocketGraceful();


    /**
     * Requires APR/native library
     *
     * @return <code>true</code> if this Protocol Handler requires the
     *         APR/native library, otherwise <code>false</code>
     */
    public boolean isAprRequired();


    /**
     * Does this ProtocolHandler support sendfile?
     *
     * @return <code>true</code> if this Protocol Handler supports sendfile,
     *         otherwise <code>false</code>
     */
    public boolean isSendfileSupported();


    /**
     * Add a new SSL configuration for a virtual host.
     * @param sslHostConfig the configuration
     */
    public void addSslHostConfig(SSLHostConfig sslHostConfig);


    /**
     * Find all configured SSL virtual host configurations which will be used
     * by SNI.
     * @return the configurations
     */
    public SSLHostConfig[] findSslHostConfigs();


    /**
     * Add a new protocol for used by HTTP/1.1 upgrade or ALPN.
     * @param upgradeProtocol the protocol
     */
    public void addUpgradeProtocol(UpgradeProtocol upgradeProtocol);


    /**
     * Return all configured upgrade protocols.
     * @return the protocols
     */
    public UpgradeProtocol[] findUpgradeProtocols();
}
