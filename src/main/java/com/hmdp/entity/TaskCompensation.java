package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 补偿任务账本：把对账/重投线索持久化到数据库。
 * Redis 预扣元数据是快路径，这张表是账本——记录重试次数、下次执行时间、终态与错误，供审计与人工介入。
 */
@Data
@Accessors(chain = true)
@TableName("tb_task_compensation")
public class TaskCompensation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型：秒杀订单落库补偿。 */
    public static final String BIZ_TYPE_ORDER_CREATE = "ORDER_CREATE";

    /** 状态：待处理。 */
    public static final int STATUS_PENDING = 0;
    /** 状态：已成功闭环。 */
    public static final int STATUS_SUCCESS = 1;
    /** 状态：重试中。 */
    public static final int STATUS_RETRYING = 2;
    /** 状态：终态失败，等待人工处理。 */
    public static final int STATUS_FAILED = 3;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务类型 */
    private String bizType;

    /** 业务ID（如订单ID） */
    private Long bizId;

    /** 状态：0待处理 1已成功 2重试中 3终态失败（人工） */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    /** 下次执行时间 */
    private LocalDateTime nextExecuteTime;

    /** 最近一次错误 */
    private String lastError;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
