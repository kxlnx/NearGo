package com.hmdp.listener.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.TaskCompensation;
import com.hmdp.mapper.TaskCompensationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 补偿任务账本记录器：规则树节点在对账过程中同步记账。
 *
 * <p>记录规则：
 * <ul>
 *   <li>重投成功：不存在则创建账本行，存在则累加次数并回到"重试中"；</li>
 *   <li>订单落库 / 预扣释放：账本闭环为"成功"（从未重投过的订单没有账本行，更新影响 0 行）；</li>
 *   <li>重投达到上限：账本标记"终态失败"，停止自动重试，等待人工处理。</li>
 * </ul>
 */
@Service
public class TaskCompensationRecorder {

    @Resource
    private TaskCompensationMapper taskCompensationMapper;

    /** 重投最小间隔（秒），用于推算账本上的下次执行时间。 */
    @Value("${seckill.reconcile.retry-interval-seconds:60}")
    private long retryIntervalSeconds;

    /** 记录一次重投：不存在则创建账本行，存在则更新次数与下次执行时间。 */
    public void markRetrying(String bizType, Long bizId, int retryCount, String lastError) {
        TaskCompensation existing = findByBiz(bizType, bizId);
        if (existing == null) {
            taskCompensationMapper.insert(new TaskCompensation()
                    .setBizType(bizType)
                    .setBizId(bizId)
                    .setStatus(TaskCompensation.STATUS_RETRYING)
                    .setRetryCount(retryCount)
                    .setNextExecuteTime(LocalDateTime.now().plusSeconds(retryIntervalSeconds))
                    .setLastError(lastError));
            return;
        }
        taskCompensationMapper.update(null, new LambdaUpdateWrapper<TaskCompensation>()
                .eq(TaskCompensation::getBizType, bizType)
                .eq(TaskCompensation::getBizId, bizId)
                .set(TaskCompensation::getStatus, TaskCompensation.STATUS_RETRYING)
                .set(TaskCompensation::getRetryCount, retryCount)
                .set(TaskCompensation::getNextExecuteTime, LocalDateTime.now().plusSeconds(retryIntervalSeconds))
                .set(TaskCompensation::getLastError, lastError));
    }

    /** 订单已落库 / 预扣已释放：账本闭环为成功。 */
    public void markSuccess(String bizType, Long bizId) {
        taskCompensationMapper.update(null, new LambdaUpdateWrapper<TaskCompensation>()
                .eq(TaskCompensation::getBizType, bizType)
                .eq(TaskCompensation::getBizId, bizId)
                .ne(TaskCompensation::getStatus, TaskCompensation.STATUS_SUCCESS)
                .set(TaskCompensation::getStatus, TaskCompensation.STATUS_SUCCESS)
                .set(TaskCompensation::getNextExecuteTime, null)
                .set(TaskCompensation::getLastError, null));
    }

    /** 超过最大重试次数：账本标记终态失败，转人工。 */
    public void markFailed(String bizType, Long bizId, String lastError) {
        taskCompensationMapper.update(null, new LambdaUpdateWrapper<TaskCompensation>()
                .eq(TaskCompensation::getBizType, bizType)
                .eq(TaskCompensation::getBizId, bizId)
                .set(TaskCompensation::getStatus, TaskCompensation.STATUS_FAILED)
                .set(TaskCompensation::getNextExecuteTime, null)
                .set(TaskCompensation::getLastError, lastError));
    }

    private TaskCompensation findByBiz(String bizType, Long bizId) {
        return taskCompensationMapper.selectOne(new LambdaQueryWrapper<TaskCompensation>()
                .eq(TaskCompensation::getBizType, bizType)
                .eq(TaskCompensation::getBizId, bizId)
                .last("limit 1"));
    }
}
