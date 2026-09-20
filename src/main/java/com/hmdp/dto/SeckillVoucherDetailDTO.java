package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀优惠券详情页数据：优惠券基础信息 + 秒杀信息。
 * 作为 Caffeine(5s) - Redis(30s) - MySQL 两级缓存的缓存对象。
 */
@Data
@Accessors(chain = true)
public class SeckillVoucherDetailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long shopId;

    private String title;

    private String subTitle;

    private String rules;

    private Long payValue;

    private Long actualValue;

    private Integer type;

    private Integer status;

    private Integer stock;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;
}
