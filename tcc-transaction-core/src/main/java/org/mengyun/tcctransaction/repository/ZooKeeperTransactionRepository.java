package org.mengyun.tcctransaction.repository;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.mengyun.tcctransaction.Transaction;
import org.mengyun.tcctransaction.repository.helper.TransactionSerializer;
import org.mengyun.tcctransaction.serializer.KryoPoolSerializer;
import org.mengyun.tcctransaction.serializer.ObjectSerializer;

import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by changming.xie on 2/18/16.
 */
public class ZooKeeperTransactionRepository extends CachableTransactionRepository {

    /**
     * Zookeeper 服务器地址数组
     */
    private String zkServers;
    /**
     * Zookeeper 超时时间
     */
    private int zkTimeout;
    /**
     * TCC 存储 Zookeeper 根目录
     * 存储 Zookeeper 根目录，类似 JdbcTransactionRepository 的 domain 属性。
     * 一个事务存储到 Zookeeper，使用 Zookeeper 的持久数据节点。
     * path：${zkRootPath} + / + ${xid}
     */
    private String zkRootPath = "/tcc";
    /**
     * Zookeeper 连接
     */
    private volatile ZooKeeper zk;

    private ObjectSerializer serializer = new KryoPoolSerializer();

    public ZooKeeperTransactionRepository() {
        super();
    }

    public void setSerializer(ObjectSerializer serializer) {
        this.serializer = serializer;
    }

    public void setZkRootPath(String zkRootPath) {
        this.zkRootPath = zkRootPath;
    }

    public void setZkServers(String zkServers) {
        this.zkServers = zkServers;
    }

    public void setZkTimeout(int zkTimeout) {
        this.zkTimeout = zkTimeout;
    }

    @Override
    protected int doCreate(Transaction transaction) {

        try {
            getZk().create(getTxidPath(transaction.getXid()),
                    TransactionSerializer.serialize(serializer, transaction), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            return 1;
        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }

    @Override
    protected int doUpdate(Transaction transaction) {
        try {
            // 设置最后更新时间 和 最新版本号
            transaction.updateTime();
            transaction.updateVersion();

            // -2 的原因是，Transaction 的版本从 1 开始，而 Zookeeper 数据节点版本从 0 开始；上面调用 transaction.updateVersion() 又多加了1
            Stat stat = getZk().setData(getTxidPath(transaction.getXid()), TransactionSerializer.serialize(serializer, transaction),
                    (int) transaction.getVersion() - 2);
            return 1;
        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }

    @Override
    protected int doDelete(Transaction transaction) {
        try {
            // -1 的原因是，Transaction 的版本从 1 开始，而 Zookeeper 数据节点版本从 0 开始；
            getZk().delete(getTxidPath(transaction.getXid()), (int) transaction.getVersion() - 1);
            return 1;
        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
    }

    @Override
    protected Transaction doFindOne(Xid xid) {
        byte[] content = null;
        try {
            Stat stat = new Stat();
            content = getZk().getData(getTxidPath(xid), false, stat);
            Transaction transaction = TransactionSerializer.deserialize(serializer, content);
            return transaction;
        } catch (KeeperException.NoNodeException e) {

        } catch (Exception e) {
            throw new TransactionIOException(e);
        }
        return null;
    }

    /**
     * 获取超过指定时间的事务集合
     *
     * @param date 指定时间
     * @return 事务集合
     */
    @Override
    protected List<Transaction> doFindAllUnmodifiedSince(Date date) {
        // 获得所有事务
        List<Transaction> allTransactions = doFindAll();
        // 过滤时间
        List<Transaction> allUnmodifiedSince = new ArrayList<Transaction>();

        for (Transaction transaction : allTransactions) {
            if (transaction.getLastUpdateTime().compareTo(date) < 0) {
                allUnmodifiedSince.add(transaction);
            }
        }

        return allUnmodifiedSince;
    }

    protected List<Transaction> doFindAll() {
        List<Transaction> transactions = new ArrayList<Transaction>();

        // 获得 ${zkRootPath} 目录下所有的数据节点( 即，事务 )
        List<String> znodePaths = null;
        try {
            znodePaths = getZk().getChildren(zkRootPath, false);
        } catch (Exception e) {
            throw new TransactionIOException(e);
        }

        // 反序列化 Transaction
        for (String znodePath : znodePaths) {
            byte[] content = null;
            try {
                Stat stat = new Stat();
                content = getZk().getData(getTxidPath(znodePath), false, stat);
                Transaction transaction = TransactionSerializer.deserialize(serializer, content);
                transactions.add(transaction);
            } catch (Exception e) {
                throw new TransactionIOException(e);
            }
        }

        return transactions;
    }

    private ZooKeeper getZk() {
        if (zk == null) {
            synchronized (ZooKeeperTransactionRepository.class) {
                if (zk == null) {
                    try {
                        // 创建 Zookeeper 连接
                        zk = new ZooKeeper(zkServers, zkTimeout, new Watcher() {
                            @Override
                            public void process(WatchedEvent watchedEvent) {

                            }
                        });

                        // 创建 Zookeeper 根目录
                        Stat stat = zk.exists(zkRootPath, false);
                        if (stat == null) {
                            zk.create(zkRootPath, zkRootPath.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        }
                    } catch (Exception e) {
                        throw new TransactionIOException(e);
                    }
                }
            }
        }
        return zk;
    }

    /**
     * 获得事务路径
     *
     * @param xid 事务编号
     * @return 路径
     */
    private String getTxidPath(Xid xid) {
        return String.format("%s/%s", zkRootPath, xid);
    }

    /**
     * 获得事务路径
     *
     * @param znodePath xid.toString()
     * @return 路径
     */
    private String getTxidPath(String znodePath) {
        return String.format("%s/%s", zkRootPath, znodePath);
    }


}
