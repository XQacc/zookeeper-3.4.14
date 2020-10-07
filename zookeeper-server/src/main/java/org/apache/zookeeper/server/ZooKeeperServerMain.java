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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.JMException;

import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

/**
 * This class starts and runs a standalone ZooKeeperServer.
 */
@InterfaceAudience.Public
public class ZooKeeperServerMain {
    private static final Logger LOG =
        LoggerFactory.getLogger(ZooKeeperServerMain.class);

    private static final String USAGE =
        "Usage: ZooKeeperServerMain configfile | port datadir [ticktime] [maxcnxns]";

    private ServerCnxnFactory cnxnFactory;

    /*
     * Start up the ZooKeeper server.
     *
     * @param args the configfile or the port datadir [ticktime]
     */
    public static void main(String[] args) {
        ZooKeeperServerMain main = new ZooKeeperServerMain();
        try {
            main.initializeAndRun(args);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid arguments, exiting abnormally", e);
            LOG.info(USAGE);
            System.err.println(USAGE);
            System.exit(2);
        } catch (ConfigException e) {
            LOG.error("Invalid config, exiting abnormally", e);
            System.err.println("Invalid config, exiting abnormally");
            System.exit(2);
        } catch (Exception e) {
            LOG.error("Unexpected exception, exiting abnormally", e);
            System.exit(1);
        }
        LOG.info("Exiting normally");
        System.exit(0);
    }

    protected void initializeAndRun(String[] args)
        throws ConfigException, IOException
    {
        try {
            //看不懂
            ManagedUtil.registerLog4jMBeans();
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }

        ServerConfig config = new ServerConfig();
        if (args.length == 1) {//重新去去读一遍配置，前面不是读过了吗？干嘛还要读？
            // 反正就是从QuorumPeerConfig读取配置并放到ServerConfig中。
            // 后来这里明白了QuorumPeerConfig是用于读集群的配置文件，ServerConfig适用于都单机的配置文件，因为配置有所不同，所以对应的结构体就会有变化。
            config.parse(args[0]);
        } else {
            config.parse(args);
        }
        //通过配置文件启动
        runFromConfig(config);
    }

    /**
     * Run from a ServerConfig.
     * @param config ServerConfig to use.
     * @throws IOException
     */
    public void runFromConfig(ServerConfig config) throws IOException {
        LOG.info("这是单机模式！！！！");
        LOG.info("Starting server");
        //事务日志结构体
        FileTxnSnapLog txnLog = null;
        try {
            // Note that this thread isn't going to be doing anything else,
            // so rather than spawning another thread, we will just call
            // run() in this thread.
            // create a file logger url from the command line args
            final ZooKeeperServer zkServer = new ZooKeeperServer();
            // Registers shutdown handler which will be used to know the
            // server error or shutdown state changes.
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            zkServer.registerServerShutdownHandler(
                    new ZooKeeperServerShutdownHandler(shutdownLatch));
            //server的相关属性设置
            txnLog = new FileTxnSnapLog(new File(config.dataLogDir), new File(
                    config.dataDir));
            txnLog.setServerStats(zkServer.serverStats());
            zkServer.setTxnLogFactory(txnLog);
            zkServer.setTickTime(config.tickTime);
            zkServer.setMinSessionTimeout(config.minSessionTimeout);
            zkServer.setMaxSessionTimeout(config.maxSessionTimeout);
            //通过工厂模式去生产一个实际的zkserver的工厂。
            cnxnFactory = ServerCnxnFactory.createFactory();
            //生产了一个NIO的cnxn的工厂出来
            //配置文件中配置maxClientCnxns，config.getMaxClientCnxns()就是它的值。默认60
            //这里其实就开放了让client连接。
            cnxnFactory.configure(config.getClientPortAddress(),
                    config.getMaxClientCnxns());
            cnxnFactory.startup(zkServer);
            // Watch status of ZooKeeper server. It will do a graceful shutdown
            // if the server is not running or hits an internal error.
            //执行完await方法就能操作了。为什么这个时候就可以了？首先它肯定是等待一个子线程结束。当我们debug到这的时候，这里的主线程就会阻塞到这里。
            //阻塞到这里为什么client执行命令会卡住呢？
            //首先我们注释掉shutdownLatch.await();那么一启动就会结束，Exiting normally。那么这个方法的作用就很明显了，就是为了让子线程一直执行。
            //为什么一定要执行await()方法之后命令才会处理呢？能连上说明2181端口是开启的。请求处理链的线程也是开启了的那么调试的时候就是处理链到端口阻塞或者返回的时候阻塞了。
            //通过在PrepRequestProcessor的run方法前加上打印符发现是执行了await后才打印的，说明这个时候是阻塞了处理链到端口的地方。
            //com.xq.test.ServerSocketChannelTest大概模拟了一下，也许不对，可能是执行await方法之后才去处理端口接受的数据的，那我怎么证明呢？
            //首先肯定有方法接受并处理数据。然后定位到org.apache.zookeeper.server.NIOServerCnxnFactory.run方法中。
            //我在await方法加了打印，也在NIOServerCnxnFactory.run加了打印。从多次执行打印看就是先接受，处理，所以线程是没有被阻塞的，说明我想错了。
            //实际情况应该是我dedug的当断点到这的时候程序已经是暂停状态，它是不会跑代码的，所以是个错误的感觉，白写这么多！
            //TimeUnit.MINUTES.sleep(2);照样能用。。。注释不删，就当是教训吧。
            System.out.println("await方法");
            shutdownLatch.await();
//            TimeUnit.MINUTES.sleep(2);
            shutdown();

            cnxnFactory.join();
            if (zkServer.canShutdown()) {
                zkServer.shutdown(true);
            }
        } catch (InterruptedException e) {
            // warn, but generally this is ok
            LOG.warn("Server interrupted", e);
        } finally {
            if (txnLog != null) {
                txnLog.close();
            }
        }
    }

    /**
     * Shutdown the serving instance
     */
    protected void shutdown() {
        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }
    }

    // VisibleForTesting
    ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }
}
