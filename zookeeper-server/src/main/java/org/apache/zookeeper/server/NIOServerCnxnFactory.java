/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NIOServerCnxnFactory extends ServerCnxnFactory implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(NIOServerCnxnFactory.class);

    static {
        /**
         * this is to avoid the jvm bug:
         * NullPointerException in Selector.open()
         * http://bugs.sun.com/view_bug.do?bug_id=6427854
         */
        try {
            Selector.open().close();
        } catch(IOException ie) {
            LOG.error("Selector failed to open", ie);
        }
    }

    ServerSocketChannel ss;

    final Selector selector = Selector.open();

    /**
     * We use this buffer to do efficient socket I/O. Since there is a single
     * sender thread per NIOServerCnxn instance, we can use a member variable to
     * only allocate it once.
    */
    final ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);

    final HashMap<InetAddress, Set<NIOServerCnxn>> ipMap =
        new HashMap<InetAddress, Set<NIOServerCnxn>>( );

    int maxClientCnxns = 60;

    /**
     * Construct a new server connection factory which will accept an unlimited number
     * of concurrent connections from each client (up to the file descriptor
     * limits of the operating system). startup(zks) must be called subsequently.
     * @throws IOException
     */
    public NIOServerCnxnFactory() throws IOException {
    }

    Thread thread;
    @Override
    public void configure(InetSocketAddress addr, int maxcc) throws IOException {
        //配置sasl认证
        configureSaslLogin();

        //创建一个线程。ZooKeeperThread继承了Thread
        thread = new ZooKeeperThread(this, "NIOServerCxn.Factory:" + addr);
        //设置为守护线程
        thread.setDaemon(true);
        maxClientCnxns = maxcc;
        //Java NIO中的 ServerSocketChannel 是一个可以监听新进来的TCP连接的通道, 就像标准IO中的ServerSocket一样。
        //建立NIO通道。
        this.ss = ServerSocketChannel.open();
        // 设置参数 使得在同一个主机上关闭了服务器程序，紧接着再启动该服务器程序时可以顺利绑定到相同的端口
        ss.socket().setReuseAddress(true);
        LOG.info("binding to port " + addr);
        //绑定端口
        ss.socket().bind(addr);
        // 使ServerSocketChannel工作处于非阻塞模式
        ss.configureBlocking(false);
        //注册
        ss.register(selector, SelectionKey.OP_ACCEPT);
    }

    /** {@inheritDoc} */
    public int getMaxClientCnxnsPerHost() {
        return maxClientCnxns;
    }

    /** {@inheritDoc} */
    public void setMaxClientCnxnsPerHost(int max) {
        maxClientCnxns = max;
    }

    @Override
    public void start() {
        // ensure thread is started once and only once
        if (thread.getState() == Thread.State.NEW) {
            thread.start();
        }
    }

    @Override
    public void startup(ZooKeeperServer zks) throws IOException,
            InterruptedException {
        //启动thread线程。
        start();
        //设置zkserver的属性
        setZooKeeperServer(zks);
        //加载数据。如果有数据，直接返回。否则根据事务和快照进行快速恢复。
        zks.startdata();
        //启动ZooKeeperServer服务器
        zks.startup();
    }

    @Override
    public InetSocketAddress getLocalAddress(){
        return (InetSocketAddress)ss.socket().getLocalSocketAddress();
    }

    @Override
    public int getLocalPort(){
        return ss.socket().getLocalPort();
    }

    private void addCnxn(NIOServerCnxn cnxn) throws IOException {
        synchronized (cnxns) {
            cnxns.add(cnxn);
            synchronized (ipMap){
                InetAddress addr = cnxn.getSocketAddress();
                if (addr == null) {
                    throw new IOException("Socket of " + cnxn + " has been closed");
                }
                Set<NIOServerCnxn> s = ipMap.get(addr);
                if (s == null) {
                    // in general we will see 1 connection from each
                    // host, setting the initial cap to 2 allows us
                    // to minimize mem usage in the common case
                    // of 1 entry --  we need to set the initial cap
                    // to 2 to avoid rehash when the first entry is added
                    s = new HashSet<NIOServerCnxn>(2);
                    s.add(cnxn);
                    ipMap.put(addr,s);
                } else {
                    s.add(cnxn);
                }
            }
        }
    }

    public void removeCnxn(NIOServerCnxn cnxn) {
        synchronized(cnxns) {
            // Remove the related session from the sessionMap.
            long sessionId = cnxn.getSessionId();
            if (sessionId != 0) {
                sessionMap.remove(sessionId);
            }

            // if this is not in cnxns then it's already closed
            if (!cnxns.remove(cnxn)) {
                return;
            }

            synchronized (ipMap) {
                InetAddress addr = cnxn.getSocketAddress();
                if (addr != null) {
                    Set<NIOServerCnxn> s = ipMap.get(addr);
                    if (s != null) {
                        s.remove(cnxn);
                    }
            	}
            }

            unregisterConnection(cnxn);
        }
    }

    protected NIOServerCnxn createConnection(SocketChannel sock,
            SelectionKey sk) throws IOException {
        return new NIOServerCnxn(zkServer, sock, sk, this);
    }

    private int getClientCnxnCount(InetAddress cl) {
        // The ipMap lock covers both the map, and its contents
        // (that is, the cnxn sets shouldn't be modified outside of
        // this lock)
        synchronized (ipMap) {
            Set<NIOServerCnxn> s = ipMap.get(cl);
            if (s == null) return 0;
            return s.size();
        }
    }

    public void run() {
        System.out.println("接受并处理socket数据！！");
        while (!ss.socket().isClosed()) {
            try {
                selector.select(1000);
                Set<SelectionKey> selected;
                synchronized (this) {
                    selected = selector.selectedKeys();
                }
                ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(
                        selected);
                Collections.shuffle(selectedList);
                for (SelectionKey k : selectedList) {
                    if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                        SocketChannel sc = ((ServerSocketChannel) k
                                .channel()).accept();
                        InetAddress ia = sc.socket().getInetAddress();
                        int cnxncount = getClientCnxnCount(ia);
                        if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){
                            LOG.warn("Too many connections from " + ia
                                     + " - max is " + maxClientCnxns );
                            sc.close();
                        } else {
                            LOG.info("Accepted socket connection from "
                                     + sc.socket().getRemoteSocketAddress());
                            sc.configureBlocking(false);
                            SelectionKey sk = sc.register(selector,
                                    SelectionKey.OP_READ);
                            NIOServerCnxn cnxn = createConnection(sc, sk);
                            sk.attach(cnxn);
                            addCnxn(cnxn);
                        }
                    } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                        NIOServerCnxn c = (NIOServerCnxn) k.attachment();
                        c.doIO(k);
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Unexpected ops in select "
                                      + k.readyOps());
                        }
                    }
                }
                selected.clear();
            } catch (RuntimeException e) {
                LOG.warn("Ignoring unexpected runtime exception", e);
            } catch (Exception e) {
                LOG.warn("Ignoring exception", e);
            }
        }
        closeAll();
        LOG.info("NIOServerCnxn factory exited run method");
    }

    /**
     * clear all the connections in the selector
     *
     */
    @Override
    @SuppressWarnings("unchecked")
    synchronized public void closeAll() {
        selector.wakeup();
        HashSet<NIOServerCnxn> cnxns;
        synchronized (this.cnxns) {
            cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();
        }
        // got to clear all the connections that we have in the selector
        for (NIOServerCnxn cnxn: cnxns) {
            try {
                // don't hold this.cnxns lock as deadlock may occur
                cnxn.close();
            } catch (Exception e) {
                LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                         + Long.toHexString(cnxn.sessionId), e);
            }
        }
    }

    public void shutdown() {
        try {
            ss.close();
            closeAll();
            thread.interrupt();
            thread.join();
            if (login != null) {
                login.shutdown();
            }
        } catch (InterruptedException e) {
            LOG.warn("Ignoring interrupted exception during shutdown", e);
        } catch (Exception e) {
            LOG.warn("Ignoring unexpected exception during shutdown", e);
        }
        try {
            selector.close();
        } catch (IOException e) {
            LOG.warn("Selector closing", e);
        }
        if (zkServer != null) {
            zkServer.shutdown();
        }
    }

    @Override
    public synchronized void closeSession(long sessionId) {
        selector.wakeup();
        closeSessionWithoutWakeup(sessionId);
    }

    @SuppressWarnings("unchecked")
    private void closeSessionWithoutWakeup(long sessionId) {
        NIOServerCnxn cnxn = (NIOServerCnxn) sessionMap.remove(sessionId);
        if (cnxn != null) {
            try {
                cnxn.close();
            } catch (Exception e) {
                LOG.warn("exception during session close", e);
            }
        }
    }

    @Override
    public void join() throws InterruptedException {
        thread.join();
    }

    @Override
    public Iterable<ServerCnxn> getConnections() {
        return cnxns;
    }
}
