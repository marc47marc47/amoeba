package com.meidusa.amoeba.aladdin.handler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;

import com.meidusa.amoeba.aladdin.io.ResultPacket;
import com.meidusa.amoeba.context.ProxyRuntimeContext;
import com.meidusa.amoeba.mysql.net.MysqlClientConnection;
import com.meidusa.amoeba.net.Connection;
import com.meidusa.amoeba.net.MessageHandler;
import com.meidusa.amoeba.net.Sessionable;
import com.meidusa.amoeba.net.poolable.ObjectPool;
import com.meidusa.amoeba.net.poolable.PoolableObject;

/**
 * @author struct
 * @author hexianmao
 */
public abstract class CommandMessageHandler implements MessageHandler, Sessionable {

    private static Logger           logger      = Logger.getLogger(CommandMessageHandler.class);

    protected MysqlClientConnection source;
    private long                    createTime;
    private long                    timeout;
    private long                    endTime;
    private boolean                 ended       = false;
    private ObjectPool[]            pools;
    private final Lock              lock        = new ReentrantLock(false);
    private Map<Object, ObjectPool> connPoolMap = new HashMap<Object, ObjectPool>();
    protected String                query;
    private Object                  parameter;
    protected ResultPacket          packet      = null;

    public CommandMessageHandler(MysqlClientConnection source, String query, Object parameter, ObjectPool[] pools,
                                 long timeout){
        this.source = source;
        this.query = query;
        this.pools = pools;
        this.timeout = timeout;
        this.parameter = parameter;
        packet = newResultPacket(query);
    }

    public void handleMessage(Connection conn, byte[] message) {
        // TODO Auto-generated method stub
    }

    public boolean checkIdle(long now) {
        if (timeout > 0) {
            return (now - createTime) > timeout;
        } else {
            if (ended) {
                /**
                 * 如果该session已经结束，此时如果serverConnection端还在等待所有数据访问。并且超过15s, 则需要当空闲的会话
                 * 避免由于各种原因造成服务器端没有发送数据或者已经结束的会话而ServerConnection无法返回Pool中。
                 */
                return (now - endTime) > 15000;
            }
            return false;
        }
    }

    public void startSession() throws Exception {
        if (pools.length == 1) {
            final PoolableObject conn = (PoolableObject) pools[0].borrowObject();
            connPoolMap.put(conn, pools[0]);
            MessageHandlerRunner runnable = null;
            if (conn instanceof MessageHandlerRunnerProvider) {
                MessageHandlerRunnerProvider provider = (MessageHandlerRunnerProvider) conn;
                runnable = provider.getRunner();
            } else {
                runnable = newQueryRunnable(null, conn, query, parameter, packet);
            }
            runnable.init(this);
            runnable.run();
        } else {
            // TODO 多线程合并数据问题
            final CountDownLatch latch = new CountDownLatch(pools.length);

            for (ObjectPool pool : pools) {
                final PoolableObject conn = (PoolableObject) pool.borrowObject();
                connPoolMap.put(conn, pool);
                if (logger.isDebugEnabled()) {
                    logger.debug("query:" + query);
                }
                QueryRunnable runnable = newQueryRunnable(latch, conn, query, parameter, packet);
                runnable.init(this);
                ProxyRuntimeContext.getInstance().getClientSideExecutor().execute(runnable);
            }

            if (timeout > 0) {
                latch.await(timeout, TimeUnit.MILLISECONDS);
            } else {
                latch.await();
            }
        }

        endSession();
        packet.wirteToConnection(source);
    }

    protected abstract ResultPacket newResultPacket(String query);

    protected abstract QueryRunnable newQueryRunnable(CountDownLatch latch, PoolableObject conn, String query2,
                                                      Object parameter, ResultPacket packet);

    public void endSession() {
        if (isEnded()) {
            return;
        }
        lock.lock();
        try {
            if (!ended) {
                endTime = System.currentTimeMillis();
                ended = true;
                for (Map.Entry<Object, ObjectPool> entry : connPoolMap.entrySet()) {
                    try {
                        entry.getValue().returnObject(entry.getKey());
                    } catch (Exception e) {
                        logger.error("return connection to pool error", e);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isEnded() {
        lock.lock();
        try {
            return this.ended;
        } finally {
            lock.unlock();
        }
    }

}
