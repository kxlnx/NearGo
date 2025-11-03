package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.annotation.RateLimiter;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService  voucherOrderService;
    @PostMapping("seckill/{id}")
    @RateLimiter(key = "seckill", windowSeconds = 1, count = 5, dimension = "user")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @PostMapping("pay/callback/{orderId}")
    public Result payCallback(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.payCallback(orderId)
                ? Result.ok()
                : Result.fail("支付回调处理失败或订单状态已变更");
    }

    @PostMapping("close/{orderId}")
    public Result closeTimeoutOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.closeTimeoutOrder(orderId)
                ? Result.ok()
                : Result.fail("关单处理失败或订单状态已变更");
    }
}
