package org.mengyun.tcctransaction.repository;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.mengyun.tcctransaction.OptimisticLockException;
import org.mengyun.tcctransaction.Transaction;
import org.mengyun.tcctransaction.TransactionRepository;
import org.mengyun.tcctransaction.api.TransactionXid;

import javax.transaction.xa.Xid;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by changmingxie on 10/30/15.
 */
public abstract class CachableTransactionRepository implements TransactionRepository {

    /**
     * 缓存过期时间
     */
    private int expireDuration = 120;
    /**
     * 缓存
     */
    private Cache<Xid, Transaction> transactionXidCompensableTransactionCache;

    /**
     * 添加到缓存
     *
     * @param transaction 事务
     */
    @Override
    public int create(Transaction transaction) {
        int result = doCreate(transaction);
        if (result > 0) {
            putToCache(transaction);
        }
        return result;
    }

    /**
     * 更新事务
     *
     * @param transaction 事务
     * @return 更新数量
     */
    @Override
    public int update(Transaction transaction) {
        int result = 0;

        try {
            result = doUpdate(transaction);
            if (result > 0) {
                putToCache(transaction);
            } else {
                throw new OptimisticLockException();
            }
        } finally {
            // 更新失败，移除缓存。下次访问，从存储器读取
            if (result <= 0) {
                removeFromCache(transaction);
            }
        }

        return result;
    }

    /**
     * 删除事务
     *
     * @param transaction 事务
     * @return 删除数量
     */
    @Override
    public int delete(Transaction transaction) {
        int result = 0;

        try {
            result = doDelete(transaction);

        } finally {
            removeFromCache(transaction);
        }
        return result;
    }

    /**
     * 获取事务
     *
     * @param transactionXid 事务编号
     * @return 事务
     */
    @Override
    public Transaction findByXid(TransactionXid transactionXid) {
        Transaction transaction = findFromCache(transactionXid);

        if (transaction == null) {
            transaction = doFindOne(transactionXid);

            if (transaction != null) {
                putToCache(transaction);
            }
        }

        return transaction;
    }

    /**
     * 获取超过指定时间的事务集合
     *
     * @param date 指定时间
     * @return 事务集合
     */
    @Override
    public List<Transaction> findAllUnmodifiedSince(Date date) {
        List<Transaction> transactions = doFindAllUnmodifiedSince(date);
        for (Transaction transaction : transactions) {
            putToCache(transaction);
        }
        return transactions;
    }

    public CachableTransactionRepository() {
        transactionXidCompensableTransactionCache = CacheBuilder.newBuilder().expireAfterAccess(expireDuration, TimeUnit.SECONDS).maximumSize(1000).build();
    }

    /**
     * 添加到缓存
     *
     * @param transaction 事务
     */
    protected void putToCache(Transaction transaction) {
        transactionXidCompensableTransactionCache.put(transaction.getXid(), transaction);
    }

    /**
     * 从缓存移除事务
     *
     * @param transaction 事务
     */
    protected void removeFromCache(Transaction transaction) {
        transactionXidCompensableTransactionCache.invalidate(transaction.getXid());
    }

    /**
     * 从缓存中获得事务
     *
     * @param transactionXid 事务编号
     * @return 事务
     */
    protected Transaction findFromCache(TransactionXid transactionXid) {
        return transactionXidCompensableTransactionCache.getIfPresent(transactionXid);
    }

    public void setExpireDuration(int durationInSeconds) {
        this.expireDuration = durationInSeconds;
    }

    protected abstract int doCreate(Transaction transaction);

    protected abstract int doUpdate(Transaction transaction);

    protected abstract int doDelete(Transaction transaction);

    protected abstract Transaction doFindOne(Xid xid);

    protected abstract List<Transaction> doFindAllUnmodifiedSince(Date date);
}
