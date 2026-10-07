-- 秒杀补偿任务账本（NearGo 优化十二）
-- 已导入过 hmdp.sql 的库单独执行本文件；新库直接导入 hmdp.sql 已包含该表。
CREATE TABLE IF NOT EXISTS `tb_task_compensation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_type` varchar(32) NOT NULL COMMENT '业务类型：ORDER_CREATE=秒杀订单落库补偿',
  `biz_id` bigint(20) NOT NULL COMMENT '业务ID（如订单ID）',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态：0待处理 1已成功 2重试中 3终态失败(人工)',
  `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '重试次数',
  `next_execute_time` datetime DEFAULT NULL COMMENT '下次执行时间',
  `last_error` varchar(512) DEFAULT NULL COMMENT '最近一次错误',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`,`biz_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact COMMENT='补偿任务账本';
