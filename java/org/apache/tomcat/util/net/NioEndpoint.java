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
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.NioChannel.ClosedNioChannel;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel,SocketChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private volatile ServerSocketChannel serverSock = null;

    /**
     * Stop latch used to wait for poller stop
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        } catch (Exception e) {
            log.error(sm.getString("endpoint.setAttributeError", name, value), e);
            return false;
        }
    }


    /**
     * Use System.inheritableChannel to obtain channel from stdin/stdout.
     */
    private boolean useInheritedChannel = false;
    public void setUseInheritedChannel(boolean useInheritedChannel) { this.useInheritedChannel = useInheritedChannel; }
    public boolean getUseInheritedChannel() { return useInheritedChannel; }

    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * NO-OP.
     *
     * @param pollerThreadCount Unused
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public void setPollerThreadCount(int pollerThreadCount) { }
    /**
     * Always returns 1.
     *
     * @return Always 1.
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public int getPollerThreadCount() { return 1; }

    private long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout) { this.selectorTimeout = timeout;}
    public long getSelectorTimeout() { return this.selectorTimeout; }

    /**
     * The socket poller.
     */
    private Poller poller = null;


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     *         for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (poller == null) {
            return 0;
        } else {
            return poller.getKeyCount();
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     */
    @Override
    public void bind() throws Exception {
        // 初始化NIO socket
        initServerSocket();

        // 设置停止计数器
        setStopLatch(new CountDownLatch(1));

        // 有必要的话 初始化 ssl
        initialiseSsl();

        // 打开多路复用器池
        selectorPool.open(getName());
    }

    // Separated out to make it easier for folks that extend NioEndpoint to
    // implement custom [server]sockets
    protected void initServerSocket() throws Exception {
        if (!getUseInheritedChannel()) {
            serverSock = ServerSocketChannel.open();
            socketProperties.setProperties(serverSock.socket());
            InetSocketAddress addr = new InetSocketAddress(getAddress(), getPortWithOffset());
            // todo 为什么要用 socket 而不是 ServerSocketChannel 绑定
            serverSock.socket().bind(addr,getAcceptCount());
        } else {
            // Retrieve the channel provided by the OS
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        }
        // 已阻塞式的创建连接
        serverSock.configureBlocking(true); //mimic APR behavior
    }


    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     */
    @Override
    public void startInternal() throws Exception {

        // NioEndpoint 一共包含 LimitLatch、Acceptor、Poller、SocketProcessor 和 Executor 共 5 个组件

        // LimitLatch、Acceptor、Poller 和 Executor 在本方法创建
        // SocketProcessor 在 Poller run 方法创建 并提交给 Executor 执行

        if (!running) {
            running = true;
            paused = false;


            // 下面3个是对象池的使用

            // 创建一个线程安全的栈 用来缓存 SocketProcessor
            if (socketProperties.getProcessorCache() != 0) {
                processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                        socketProperties.getProcessorCache());
            }
            // 创建一个线程安全的栈 用来缓存 PollerEvent
            if (socketProperties.getEventCache() != 0) {
                eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                        socketProperties.getEventCache());
            }
            // 创建一个线程安全的栈 用来缓存 NioChannel
            if (socketProperties.getBufferPool() != 0) {
                nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                        socketProperties.getBufferPool());
            }

            // 创建内部线程池
            if (getExecutor() == null) {
                createExecutor();
            }

            // 初始化 LimitLatch 默认是10000
            initializeConnectionLatch();

            // 开启 Poller 轮询器线程
            // Poller 的主要功能是
            poller = new Poller();
            Thread pollerThread = new Thread(poller, getName() + "-ClientPoller");
            pollerThread.setPriority(threadPriority);
            pollerThread.setDaemon(true);
            pollerThread.start();

            // 开启 Acceptor 线程
            startAcceptorThread();

            // Acceptor 阻塞式的接受客户端的链接
            // 每接受一个连接 就把SocketChannel 封装成 NioChannel ， NioSocketWrapper
            // 再把它封装成一个 PollerEvent  注册到 Poller 轮询器的 events  队列上去
            // 这样构成了 生产者-消费者模式的 生产

            // Poller 轮询器不断消费 events 队列里的 PollerEvent 事件
            // PollerEvent 的作用就是把自己感兴趣的 Nio 事件 注册到 多路复用器上
            // 接着 Poller 轮询器 非阻塞式的拉取 多路复用器上已经就绪的感兴趣的Nio事件
            // 然后挨个处理 SelectKey   把他们封装成 SocketProcessor 提交到线程池


            // 线程池处理的时候
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            if (poller != null) {
                poller.destroy();
                poller = null;
            }
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor();
            if (eventCache != null) {
                eventCache.clear();
                eventCache = null;
            }
            if (nioChannels != null) {
                nioChannels.clear();
                nioChannels = null;
            }
            if (processorCache != null) {
                processorCache.clear();
                processorCache = null;
            }
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for " +
                    new InetSocketAddress(getAddress(),getPortWithOffset()));
        }
        if (running) {
            stop();
        }
        doCloseServerSocket();
        destroySsl();
        super.unbind();
        if (getHandler() != null ) {
            getHandler().recycle();
        }
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for " +
                    new InetSocketAddress(getAddress(), getPortWithOffset()));
        }
    }


    @Override
    protected void doCloseServerSocket() throws IOException {
        if (!getUseInheritedChannel() && serverSock != null) {
            // Close server socket
            serverSock.close();
        }
        serverSock = null;
    }

    // ------------------------------------------------------ Protected Methods

    protected NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    protected SynchronizedStack<NioChannel> getNioChannels() {
        return nioChannels;
    }


    protected Poller getPoller() {
        return poller;
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     * Process the specified connection.
     * @param socket The socket channel
     * @return <code>true</code> if the socket was correctly configured
     *  and processing may continue, <code>false</code> if the socket needs to be
     *  close immediately
     */
    @Override
    protected boolean setSocketOptions(SocketChannel socket) {
        // 处理这个连接
        try {
            // Disable blocking, polling will be used
            // 将这个连接设置为非阻塞
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            socketProperties.setProperties(sock);

            NioChannel channel = null;
            // 先尝试从缓存栈里面弹出一个 NioChannel
            // 从对象池中取出一个NioChannel
            if (nioChannels != null) {
                channel = nioChannels.pop();
            }

            // 缓存栈里没有 NioChannel 则new一个
            if (channel == null) {

                // 创建 SocketBuffer 处理器
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),
                        socketProperties.getAppWriteBufSize(),
                        socketProperties.getDirectBuffer());
                // 创建 NioChannel
                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                // 将缓存栈里的 NioChannel 引用这个连接
                // 被重置基本属性
                channel.setIOChannel(socket);
                channel.reset();
            }

            /*
             *  Http11Processor 并不是直接读取 Channel 的。
             *  这是因为 Tomcat 支持同步非阻塞 I/O 模型和异步 I/O 模型，
             *  在 Java API 中，相应的 Channel 类也是不一样的，
             *  比如有 AsynchronousSocketChannel 和 SocketChannel，
             *  为了对 Http11Processor 屏蔽这些差异，
             *  Tomcat 设计了一个包装类叫作 SocketWrapper，
             *  Http11Processor 只调用 SocketWrapper 的方法去读写数据
             */

            // 创建 NioChannel 与 Endpoint 的包装器
            NioSocketWrapper socketWrapper = new NioSocketWrapper(channel, this);
            // NioChannel 引用 NioSocketWrapper
            channel.setSocketWrapper(socketWrapper);

            // NioSocketWrapper 设置读超时时间
            socketWrapper.setReadTimeout(getConnectionTimeout());
            // NioSocketWrapper 设置写超时时间
            socketWrapper.setWriteTimeout(getConnectionTimeout());
            socketWrapper.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            // 是否使用ssl
            socketWrapper.setSecure(isSSLEnabled());

            // 把Nio 注册到 轮询器上
            // 在同一时刻只有一个线程执行这个操作  所以是线程安全的
            poller.register(channel, socketWrapper);
            return true;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error(sm.getString("endpoint.socketOptionsError"), t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
        }
        // Tell to close the socket
        return false;
    }


    @Override
    protected void closeSocket(SocketChannel socket) {
        countDownConnection();
        try {
            socket.close();
        } catch (IOException ioe) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.err.close"), ioe);
            }
        }
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    @Override
    protected SocketChannel serverSocketAccept() throws Exception {
        return serverSock.accept();
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent implements Runnable {

        private NioChannel socket;
        private int interestOps;

        public PollerEvent(NioChannel ch, int intOps) {
            reset(ch, intOps);
        }

        public void reset(NioChannel ch, int intOps) {
            socket = ch;
            interestOps = intOps;
        }

        public void reset() {
            reset(null, 0);
        }

        @Override
        public void run() {

            // 如果感兴趣的事件为 OP_REGISTER
            if (interestOps == OP_REGISTER) {
                // SocketChannel 往 Selector 上注册 读事件
                // 并把 NioSocketWrapper 传递给 SelectionKey
                try {
                    socket.getIOChannel().register(socket.getSocketWrapper().getPoller().getSelector(), SelectionKey.OP_READ, socket.getSocketWrapper());
                } catch (Exception x) {
                    log.error(sm.getString("endpoint.nio.registerFail"), x);
                }
            } else {
                // 如果感兴趣的事件不是 OP_REGISTER

                // 获取 SocketChannel SelectionKey
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getSocketWrapper().getPoller().getSelector());
                try {
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        try {
                            socket.socketWrapper.close();
                        } catch (Exception ignore) {
                        }
                    } else {
                        final NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper != null) {
                            // We are registering the key to start with, reset the fairness counter.
                            int ops = key.interestOps() | interestOps;
                            socketWrapper.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            socket.getSocketWrapper().getPoller().cancelledKey(key, socket.getSocketWrapper());
                        }
                    }
                } catch (CancelledKeyException ckx) {
                    try {
                        socket.getSocketWrapper().getPoller().cancelledKey(key, socket.getSocketWrapper());
                    } catch (Exception ignore) {}
                }
            }
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socket.getSocketWrapper() +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * 本质是一个 Selector，也跑在单独线程里。
     * Poller 在内部维护一个 Channel 数组，它在一个死循环里不断检测 Channel 的数据就绪状态
     * 一旦有 Channel 可读，就生成一个 SocketProcessor 任务对象扔给 Executor 去处理
     *
     * 注意 这是一个成员内部类
     */
    public class Poller implements Runnable {


        /**
         *  多路复用器
         */
        private Selector selector;

        /**
         *  线程安全的队列保存轮询事件
         */
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();

        /**
         *  轮询器是否关闭
         */
        private volatile boolean close = false;
        // Optimize expiration handling
        private long nextExpiration = 0;

        private AtomicLong wakeupCounter = new AtomicLong(0);

        /**
         *  监听的key的数量
         */
        private volatile int keyCount = 0;

        public Poller() throws IOException {
            this.selector = Selector.open();
        }

        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector; }

        /**
         * 销毁轮询器
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        /**
         *  把轮训事件保存到线程安全的队列中
         *  如果调用的多路复用器的select的地方为阻塞的 则唤醒它
         */
        private void addEvent(PollerEvent event) {
            events.offer(event);
            if (wakeupCounter.incrementAndGet() == 0) {
                selector.wakeup();
            }
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socketWrapper to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(NioSocketWrapper socketWrapper, int interestOps) {
            PollerEvent r = null;
            if (eventCache != null) {
                r = eventCache.pop();
            }
            if (r == null) {
                r = new PollerEvent(socketWrapper.getSocket(), interestOps);
            } else {
                r.reset(socketWrapper.getSocket(), interestOps);
            }
            addEvent(r);
            if (close) {
                processSocket(socketWrapper, SocketEvent.STOP, false);
            }
        }

        /**
         *  挨个处理同步队列中的轮询事件
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        public boolean events() {
            boolean result = false;

            // 生产者消费者
            // Poller 轮询器 消费 xxx生产的事件
            // 捋一下脉络
            PollerEvent pe = null;
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++ ) {
                result = true;
                try {

                    // 处理轮询事件  实际上是在多路复用器上注册 read 事件
                    pe.run();
                    // 重置轮询事件
                    pe.reset();

                    // 如果Endpoint的状态是正常的 则把这个时间缓存起来
                    if (running && !paused && eventCache != null) {
                        eventCache.push(pe);
                    }
                } catch ( Throwable x ) {
                    log.error(sm.getString("endpoint.nio.pollerEventError"), x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket    The newly created socket
         * @param socketWrapper The socket wrapper
         */
        public void register(final NioChannel socket, final NioSocketWrapper socketWrapper) {

            // 参数 NioChannel NioSocketWrapper  本身就是相互引用的


            // 设置感兴趣的事件为 SelectionKey.OP_READ
            // 但是此时还没有注册到多路复用器上去
            socketWrapper.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.

            // 对象池取出一个对象
            // 尝试从缓存栈里弹出一个轮询器事件
            PollerEvent r = null;
            if (eventCache != null) {
                r = eventCache.pop();
            }

            // 缓存中没有 则new一个 并重置属性
            if (r == null) {
                r = new PollerEvent(socket, OP_REGISTER);
            } else {
                // 把缓存里面的事件的属性重置
                r.reset(socket, OP_REGISTER);
            }

            // 保存到线程安全的队列  供 run 方法使用
            // 并且唤醒轮询器线程
            addEvent(r);
        }

        public void cancelledKey(SelectionKey sk, SocketWrapperBase<NioChannel> socketWrapper) {
            try {
                if (socketWrapper != null) {
                    socketWrapper.close();
                }
                if (sk != null) {
                    sk.attach(null);
                    if (sk.isValid()) {
                        sk.cancel();
                    }
                    // The SocketChannel is also available via the SelectionKey. If
                    // it hasn't been closed in the block above, close it now.
                    if (sk.channel().isOpen()) {
                        sk.channel().close();
                    }
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
                }
            }
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         */
        @Override
        public void run() {

            // Poller 线程的主要职责
            // 1. 消费events队列的事件
            //     把事件中的SocketChannel 注册到多路复用器上
            // 2. 检测多路复用器上已经就绪的I/O事件
            //     处理这些事件()

            // Loop until destroy() is called
            // 循环检测是否有事件需要处理
            while (true) {

                boolean hasEvents = false;

                try {

                    // 如果是正常的
                    if (!close) {

                        // 消费events队列的事件  把 SocketChannel 注册到多路复用器上
                        hasEvents = events();

                        // 拉取多路复用器上的事件
                        // 区分阻塞还是非阻塞的拉取
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            // If we are here, means we have other stuff to do
                            // Do a non blocking select
                            keyCount = selector.selectNow();
                        } else {
                            keyCount = selector.select(selectorTimeout);
                        }

                        // 标记为0
                        wakeupCounter.set(0);
                    }

                    // 如果轮询器是关闭的 则关闭多路复用器
                    if (close) {

                        // 轮询器为关闭关注状态 也要消费events队列的事件
                        events();

                        // todo
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error(sm.getString("endpoint.nio.selectorLoopError"), x);
                    continue;
                }
                // Either we timed out or we woke up, process events first
                if (keyCount == 0) {
                    hasEvents = (hasEvents | events());
                }


                // 获取所有 SelectionKey
                Iterator<SelectionKey> iterator =
                    keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();

                    // 这个 attachment 是在往多路复用器上注册事件的时候附加上的
                    // @See  PollerEvent.run
                    // socket.getIOChannel().register(socket.getSocketWrapper().getPoller().getSelector(), SelectionKey.OP_READ, socket.getSocketWrapper())
                    NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    // 处理这个 SelectionKey
                    if (socketWrapper == null) {
                        iterator.remove();
                    } else {
                        iterator.remove();
                        processKey(sk, socketWrapper);
                    }
                }

                // Process timeouts
                timeout(keyCount,hasEvents);
            }

            // 未关闭 Endpoint做准备
            getStopLatch().countDown();
        }

        /**
         * 处理 SelectionKey
         * @param sk SelectionKey
         * @param socketWrapper  包装了 NioChannel Endpoint
         */
        protected void processKey(SelectionKey sk, NioSocketWrapper socketWrapper) {
            try {

                // 如果轮询器是关闭状态 则取消这个key
                // SelectionKey 可用  并且 socketWrapper 不等于null  才是正常情况
                // 其他情况都要 取消这个key
                if (close) {
                    cancelledKey(sk, socketWrapper);
                } else if (sk.isValid() && socketWrapper != null) {

                    // 如果SocketChannel 可读或者可写
                    if (sk.isReadable() || sk.isWritable()) {

                        // 处理文件请求
                        if (socketWrapper.getSendfileData() != null) {
                            processSendfile(sk, socketWrapper, false);


                            // 处理普通的请求
                        } else {
                            // todo
                            unreg(sk, socketWrapper, sk.readyOps());
                            boolean closeSocket = false;
                            // Read goes before write

                            // 处理普通读请求
                            if (sk.isReadable()) {

                                // 存在 readOperation 则 readOperation处理
                                if (socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        closeSocket = true;
                                    }

                                    // 一般情况下的处理逻辑
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }

                            // 处理普通写请求
                            if (!closeSocket && sk.isWritable()) {
                                if (socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        closeSocket = true;
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }

                            // 取消SocketChannel
                            if (closeSocket) {
                                cancelledKey(sk, socketWrapper);
                            }
                        }
                    }
                } else {
                    // Invalid key
                    cancelledKey(sk, socketWrapper);
                }
            } catch (CancelledKeyException ckx) {
                cancelledKey(sk, socketWrapper);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("endpoint.nio.keyProcessingError"), t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining() > 0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException(sm.getString("endpoint.sendfile.tooMuchData"));
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: " + sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                        case NONE: {
                            if (log.isDebugEnabled()) {
                                log.debug("Send file connection is being closed");
                            }
                            poller.cancelledKey(sk, socketWrapper);
                            break;
                        }
                        case PIPELINED: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, processing pipe-lined data");
                            }
                            if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                poller.cancelledKey(sk, socketWrapper);
                            }
                            break;
                        }
                        case OPEN: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            reg(sk, socketWrapper, SelectionKey.OP_READ);
                            break;
                        }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper, SelectionKey.OP_WRITE);
                    } else {
                        reg(sk, socketWrapper, SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Unable to complete sendfile request:", e);
                }
                if (!calledByProcessor && sc != null) {
                    poller.cancelledKey(sk, socketWrapper);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.sendfile.error"), t);
                if (!calledByProcessor && sc != null) {
                    poller.cancelledKey(sk, socketWrapper);
                }
                return SendfileState.ERROR;
            }
        }

        protected void unreg(SelectionKey sk, NioSocketWrapper socketWrapper, int readyOps) {
            // This is a must, so that we don't have multiple threads messing with the socket
            reg(sk, socketWrapper, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper socketWrapper, int intops) {
            sk.interestOps(intops);
            socketWrapper.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            int keycount = 0;
            try {
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper == null) {
                            // We don't support any keys without attachments
                            cancelledKey(key, null);
                        } else if (close) {
                            key.interestOps(0);
                            // Avoid duplicate stop calls
                            socketWrapper.interestOps(0);
                            cancelledKey(key, socketWrapper);
                        } else if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                  (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean isTimedOut = false;
                            boolean readTimeout = false;
                            boolean writeTimeout = false;
                            // Check for read timeout
                            if ((socketWrapper.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - socketWrapper.getLastRead();
                                long timeout = socketWrapper.getReadTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                                readTimeout = true;
                            }
                            // Check for write timeout
                            if (!isTimedOut && (socketWrapper.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - socketWrapper.getLastWrite();
                                long timeout = socketWrapper.getWriteTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                                writeTimeout = true;
                            }
                            if (isTimedOut) {
                                key.interestOps(0);
                                // Avoid duplicate timeout calls
                                socketWrapper.interestOps(0);
                                socketWrapper.setError(new SocketTimeoutException());
                                if (readTimeout && socketWrapper.readOperation != null) {
                                    if (!socketWrapper.readOperation.process()) {
                                        cancelledKey(key, socketWrapper);
                                    }
                                } else if (writeTimeout && socketWrapper.writeOperation != null) {
                                    if (!socketWrapper.writeOperation.process()) {
                                        cancelledKey(key, socketWrapper);
                                    }
                                } else if (!processSocket(socketWrapper, SocketEvent.ERROR, true)) {
                                    cancelledKey(key, socketWrapper);
                                }
                            }
                        }
                    } catch (CancelledKeyException ckx) {
                        cancelledKey(key, (NioSocketWrapper) key.attachment());
                    }
                }
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            // For logging purposes only
            long prevExp = nextExpiration;
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

    // --------------------------------------------------- Socket Wrapper Class

    /**
     *   包装了 NioChannel Endpoint
     */
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;
        private final SynchronizedStack<NioChannel> nioChannels;
        private final Poller poller;

        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            pool = endpoint.getSelectorPool();
            nioChannels = endpoint.getNioChannels();
            poller = endpoint.getPoller();
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() { return poller; }
        public int interestOps() { return interestOps; }
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if (latch == null || latch.getCount() == 0) {
                return null;
            } else {
                throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
            }
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if (latch == null || latch.getCount() == 0) {
                return new CountDownLatch(cnt);
            } else {
                throw new IllegalStateException(sm.getString("endpoint.nio.latchMustBeZero"));
            }
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch, cnt); }
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch, cnt); }

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if (latch == null) {
                throw new IllegalStateException(sm.getString("endpoint.nio.nullLatch"));
            }
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout, unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch, timeout, unit); }
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch, timeout, unit); }

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData; }

        public void updateLastWrite() { lastWrite = System.currentTimeMillis(); }
        public long getLastWrite() { return lastWrite; }
        public void updateLastRead() { lastRead = System.currentTimeMillis(); }
        public long getLastRead() { return lastRead; }

        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.
            nRead = fillReadBuffer(block);
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }


        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            int nRead = populateReadBuffer(to);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            if (to.remaining() >= limit) {
                to.limit(to.position() + limit);
                nRead = fillReadBuffer(block, to);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
                }
                updateLastRead();
            } else {
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
                }
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        protected void doClose() {
            if (log.isDebugEnabled()) {
                log.debug("Calling [" + getEndpoint() + "].closeSocket([" + this + "])", new Exception());
            }
            try {
                getEndpoint().getHandler().release(this);
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.handlerRelease"), e);
                }
            }
            try {
                synchronized (getSocket()) {
                    getEndpoint().countDownConnection();
                    if (getSocket().isOpen()) {
                        getSocket().close(true);
                    }
                    if (getEndpoint().running && !getEndpoint().paused) {
                        if (nioChannels == null || !nioChannels.push(getSocket())) {
                            getSocket().free();
                        }
                    }
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.debug.channelCloseFail"), e);
                }
            } finally {
                socketBufferHandler = SocketBufferHandler.EMPTY;
                nonBlockingWriteBuffer.clear();
                reset(NioChannel.CLOSED_NIO_CHANNEL);
            }
            try {
                SendfileData data = getSendfileData();
                if (data != null && data.fchannel != null && data.fchannel.isOpen()) {
                    data.fchannel.close();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) {
                    log.error(sm.getString("endpoint.sendfile.closeError"), e);
                }
            }
        }

        private int fillReadBuffer(boolean block) throws IOException {
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }


        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            NioChannel socket = getSocket();
            if (socket instanceof ClosedNioChannel) {
                throw new ClosedChannelException();
            }
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    nRead = pool.read(to, socket, selector, getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                nRead = socket.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }


        @Override
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            NioChannel socket = getSocket();
            if (socket instanceof ClosedNioChannel) {
                throw new ClosedChannelException();
            }
            if (block) {
                long writeTimeout = getWriteTimeout();
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    pool.write(from, socket, selector, writeTimeout);
                    if (block) {
                        // Make sure we are flushed
                        do {
                            if (socket.flush(true, selector, writeTimeout)) {
                                break;
                            }
                        } while (true);
                    }
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
                // If there is data left in the buffer the socket will be registered for
                // write further up the stack. This is to ensure the socket is only
                // registered for write once as both container and user code can trigger
                // write registration.
            } else {
                if (socket.write(from) == -1) {
                    throw new EOFException();
                }
            }
            updateLastWrite();
        }


        @Override
        public void registerReadInterest() {
            getPoller().add(this, SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            getPoller().add(this, SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(getPoller().getSelector());
            // Might as well do the first write on this thread
            return getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemoteHost() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getInetAddress();
                if (inetAddr != null) {
                    remoteHost = inetAddr.getHostName();
                    if (remoteAddr == null) {
                        remoteAddr = inetAddr.getHostAddress();
                    }
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                remotePort = sc.socket().getPort();
            }
        }


        @Override
        protected void populateLocalName() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localName = inetAddr.getHostName();
                }
            }
        }


        @Override
        protected void populateLocalAddr() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                InetAddress inetAddr = sc.socket().getLocalAddress();
                if (inetAddr != null) {
                    localAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateLocalPort() {
            SocketChannel sc = getSocket().getIOChannel();
            if (sc != null) {
                localPort = sc.socket().getLocalPort();
            }
        }


        /**
         * {@inheritDoc}
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLEngine sslEngine = ch.getSslEngine();
                if (sslEngine != null) {
                    SSLSession session = sslEngine.getSession();
                    return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
                }
            }
            return null;
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }


        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }

        @Override
        protected <A> OperationState<A> newOperationState(boolean read,
                ByteBuffer[] buffers, int offset, int length,
                BlockingMode block, long timeout, TimeUnit unit, A attachment,
                CompletionCheck check, CompletionHandler<Long, ? super A> handler,
                Semaphore semaphore, VectoredIOCompletionHandler<A> completion) {
            return new NioOperationState<>(read, buffers, offset, length, block,
                    timeout, unit, attachment, check, handler, semaphore, completion);
        }

        private class NioOperationState<A> extends OperationState<A> {
            private volatile boolean inline = true;
            private NioOperationState(boolean read, ByteBuffer[] buffers, int offset, int length,
                    BlockingMode block, long timeout, TimeUnit unit, A attachment, CompletionCheck check,
                    CompletionHandler<Long, ? super A> handler, Semaphore semaphore,
                    VectoredIOCompletionHandler<A> completion) {
                super(read, buffers, offset, length, block,
                        timeout, unit, attachment, check, handler, semaphore, completion);
            }

            @Override
            protected boolean isInline() {
                return inline;
            }

            @Override
            public void run() {
                // Perform the IO operation
                // Called from the poller to continue the IO operation
                long nBytes = 0;
                if (getError() == null) {
                    try {
                        synchronized (this) {
                            if (!completionDone) {
                                // This filters out same notification until processing
                                // of the current one is done
                                if (log.isDebugEnabled()) {
                                    log.debug("Skip concurrent " + (read ? "read" : "write") + " notification");
                                }
                                return;
                            }
                            if (read) {
                                // Read from main buffer first
                                if (!socketBufferHandler.isReadBufferEmpty()) {
                                    // There is still data inside the main read buffer, it needs to be read first
                                    socketBufferHandler.configureReadBufferForRead();
                                    for (int i = 0; i < length && !socketBufferHandler.isReadBufferEmpty(); i++) {
                                        nBytes += transfer(socketBufferHandler.getReadBuffer(), buffers[offset + i]);
                                    }
                                }
                                if (nBytes == 0) {
                                    nBytes = getSocket().read(buffers, offset, length);
                                    updateLastRead();
                                }
                            } else {
                                boolean doWrite = true;
                                // Write from main buffer first
                                if (!socketBufferHandler.isWriteBufferEmpty()) {
                                    // There is still data inside the main write buffer, it needs to be written first
                                    socketBufferHandler.configureWriteBufferForRead();
                                    do {
                                        nBytes = getSocket().write(socketBufferHandler.getWriteBuffer());
                                    } while (!socketBufferHandler.isWriteBufferEmpty() && nBytes > 0);
                                    if (!socketBufferHandler.isWriteBufferEmpty()) {
                                        doWrite = false;
                                    }
                                    // Preserve a negative value since it is an error
                                    if (nBytes > 0) {
                                        nBytes = 0;
                                    }
                                }
                                if (doWrite) {
                                    nBytes = getSocket().write(buffers, offset, length);
                                    updateLastWrite();
                                }
                            }
                            if (nBytes != 0) {
                                completionDone = false;
                            }
                        }
                    } catch (IOException e) {
                        setError(e);
                    }
                }
                if (nBytes > 0) {
                    // The bytes processed are only updated in the completion handler
                    completion.completed(Long.valueOf(nBytes), this);
                } else if (nBytes < 0 || getError() != null) {
                    IOException error = getError();
                    if (error == null) {
                        error = new EOFException();
                    }
                    completion.failed(error, this);
                } else {
                    // As soon as the operation uses the poller, it is no longer inline
                    inline = false;
                    if (read) {
                        registerReadInterest();
                    } else {
                        registerWriteInterest();
                    }
                }
            }

        }

    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {

        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        @Override
        protected void doRun() {
            NioChannel socket = socketWrapper.getSocket();
            SelectionKey key = socket.getIOChannel().keyFor(socket.getSocketWrapper().getPoller().getSelector());
            Poller poller = NioEndpoint.this.poller;
            if (poller == null) {
                socketWrapper.close();
                return;
            }

            try {
                int handshake = -1;

                try {
                    if (key != null) {
                        // 三次握手完成
                        if (socket.isHandshakeComplete()) {
                            // No TLS handshaking required. Let the handler
                            // process this socket / event combination.
                            handshake = 0;
                        } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                                event == SocketEvent.ERROR) {
                            // Unable to complete the TLS handshake. Treat it as
                            // if the handshake failed.
                            handshake = -1;
                        } else {
                            handshake = socket.handshake(key.isReadable(), key.isWritable());
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            event = SocketEvent.OPEN_READ;
                        }
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake",x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }

                // 握手完成 开始处理请求
                if (handshake == 0) {
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    if (event == null) {
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        state = getHandler().process(socketWrapper, event);
                    }
                    if (state == SocketState.CLOSED) {
                        poller.cancelledKey(key, socketWrapper);
                    }
                } else if (handshake == -1 ) {
                    getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
                    poller.cancelledKey(key, socketWrapper);
                } else if (handshake == SelectionKey.OP_READ){
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE){
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                poller.cancelledKey(key, socketWrapper);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error(sm.getString("endpoint.processing.fail"), t);
                poller.cancelledKey(key, socketWrapper);
            } finally {
                socketWrapper = null;
                event = null;
                //return to cache
                if (running && !paused && processorCache != null) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class

    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
