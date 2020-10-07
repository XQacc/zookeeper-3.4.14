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

package org.apache.zookeeper.server.quorum;

import java.util.ArrayList;
import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ZooKeeperCriticalThread;
import org.apache.zookeeper.server.ZooKeeperServerListener;

/**
 * This RequestProcessor matches the incoming committed requests with the
 * locally submitted requests. The trick is that locally submitted requests that
 * change the state of the system will come back as incoming committed requests,
 * so we need to match them up.
 */
public class CommitProcessor extends ZooKeeperCriticalThread implements RequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CommitProcessor.class);

    /**
     * Requests that we are holding until the commit comes in.
     */
    LinkedList<Request> queuedRequests = new LinkedList<Request>();

    /**
     * Requests that have been committed.
     */
    LinkedList<Request> committedRequests = new LinkedList<Request>();

    RequestProcessor nextProcessor;
    ArrayList<Request> toProcess = new ArrayList<Request>();

    /**
     * This flag indicates whether we need to wait for a response to come back from the
     * leader or we just let the sync operation flow through like a read. The flag will
     * be false if the CommitProcessor is in a Leader pipeline.
     */
    boolean matchSyncs;

    public CommitProcessor(RequestProcessor nextProcessor, String id,
            boolean matchSyncs, ZooKeeperServerListener listener) {
        super("CommitProcessor:" + id, listener);
        this.nextProcessor = nextProcessor;
        this.matchSyncs = matchSyncs;
    }

    volatile boolean finished = false;

    @Override
    public void run() {
        try {
            Request nextPending = null;            
            while (!finished) {
                //假设就一条请求。
                //如果是第一次进入，则len肯定是0.因为压根就没添加过
                //第二次：如果len>0，证明是非事务性的，就直接调用FinalRequestProcessor的processRequest方法，然后就返回结果了。如果queuedRequests没有数据了（我们假设只有一条，所以没有了），那么就会进入wait方法，阻塞住这个线程。所以这里是什么时候去唤醒的？
                //这种情况一般人都是想有新的请求加到queuedRequests中吧。还记得processRequest这个方法么？上个处理器直接调用的这个方法，直接notifyAll唤醒。
                //      如果len=0，证明是事务性的。不会调用最终的处理器，然后就被wait阻塞住。这里又是什么时候被唤醒的？
                //这种情况想一想数据库，事务性的要么commit，要么rollback。所以这里肯定有一个commit咯。没错就是本类的commit方法让committedRequests>0并唤醒线程。那么啥时候调用commit方法？
                //leader确认好了就发出一个COMMIT指令，然后leader、follower包括observer就会提交这个请求！follower就从followLeader一直往下找，其它类似，现在最后一个问题，leader什么时候发出这个指令呢？
                //请看本类的commit方法注释。
                int len = toProcess.size();
                for (int i = 0; i < len; i++) {
                    nextProcessor.processRequest(toProcess.get(i));
                }
                toProcess.clear();
                synchronized (this) {
                    //第一次：前面一个处理器调用了processRequest方法，所以queuedRequests肯定有数据，nextPending也为null，第一个条件就不符合，所以为false。
                    if ((queuedRequests.size() == 0 || nextPending != null)
                            && committedRequests.size() == 0) {
                        wait();
                        continue;
                    }
                    // First check and see if the commit came in for the pending
                    // request
                    //第一次：同上
                    //第二次：这是什么情况呢？就是请求处理完了，但是可能前面的没有来得及提交。此时就需要把committedRequests取出来赋给toProcess执行下一个处理器。
                    if ((queuedRequests.size() == 0 || nextPending != null)
                            && committedRequests.size() > 0) {
                        Request r = committedRequests.remove();
                        /*
                         * We match with nextPending so that we can move to the
                         * next request when it is committed. We also want to
                         * use nextPending because it has the cnxn member set
                         * properly.
                         */
                        if (nextPending != null
                                && nextPending.sessionId == r.sessionId
                                && nextPending.cxid == r.cxid) {
                            // we want to send our version of the request.
                            // the pointer to the connection in the request
                            nextPending.hdr = r.hdr;
                            nextPending.txn = r.txn;
                            nextPending.zxid = r.zxid;
                            toProcess.add(nextPending);
                            nextPending = null;
                        } else {
                            // this request came from someone else so just
                            // send the commit packet
                            toProcess.add(r);
                        }
                    }
                }

                // We haven't matched the pending requests, so go back to
                // waiting
                if (nextPending != null) {
                    continue;
                }

                synchronized (this) {
                    // Process the next requests in the queuedRequests
                    //第一次是符合这个条件的：那么首先取出请求。我们假设是create请求。那么nextPending就有值了，break，进入第二次while循环。
                    //第二次：一般进入不了。因为非事务性的会一次取完，事务性的nextPending存在值。
                    while (nextPending == null && queuedRequests.size() > 0) {
                        Request request = queuedRequests.remove();
                        switch (request.type) {
                        case OpCode.create:
                        case OpCode.delete:
                        case OpCode.setData:
                        case OpCode.multi:
                        case OpCode.setACL:
                        case OpCode.createSession:
                        case OpCode.closeSession:
                            nextPending = request;
                            break;
                        case OpCode.sync:
                            if (matchSyncs) {
                                nextPending = request;
                            } else {
                                toProcess.add(request);
                            }
                            break;
                        default:
                            //以上不是，说明是非事务性请求（相当于读请求）
                            toProcess.add(request);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            LOG.warn("Interrupted exception while waiting", e);
        } catch (Throwable e) {
            LOG.error("Unexpected exception causing CommitProcessor to exit", e);
        }
        LOG.info("CommitProcessor exited loop!");
    }

    synchronized public void commit(Request request) {
        //follower调用链：运行的时候的逻辑：Follower.followLeader()-->Follower.processPacket()-->FollowerZooKeeperServer.commit()-->CommitProcessor.commit()
        //observer类似。不写了。
        //leader调用链：1、调用链的构造逻辑：LeaderZooKeeperServer.setupRequestProcessors()-->ProposalRequestProcessor.initialize()-->SyncRequestProcessor.run()-->SyncRequestProcessor.flush()-->AckRequestProcessor.processRequest()-->Leader.processAck()-->-->CommitProcessor.commit()
        //leader调用链：2、运行的时候的逻辑：Leader.lead()-->LearnerCnxAcceptor.run()-->LearnerHandler.run()-->Leader.processAck()这个方法就给learner发提交请求-->CommitProcessor.commit()
        //为什么只有leader有构造逻辑？因为leader才会处理事务请求，而learner是没有处理事务请求的权限的，既然不能处理，干嘛给调用链呢？对吧！
        if (!finished) {
            if (request == null) {
                LOG.warn("Committed a null!",
                         new Exception("committing a null! "));
                return;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Committing request:: " + request);
            }
            committedRequests.add(request);
            notifyAll();
        }
    }

    synchronized public void processRequest(Request request) {
        // request.addRQRec(">commit");
        if (LOG.isDebugEnabled()) {
            LOG.debug("Processing request:: " + request);
        }
        
        if (!finished) {
            queuedRequests.add(request);
            notifyAll();
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        synchronized (this) {
            finished = true;
            queuedRequests.clear();
            notifyAll();
        }
        if (nextProcessor != null) {
            nextProcessor.shutdown();
        }
    }

}
