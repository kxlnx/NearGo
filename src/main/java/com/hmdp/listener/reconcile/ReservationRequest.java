package com.hmdp.listener.reconcile;

import java.util.Map;

/**
 * 单条预扣记录的原始输入：Redis Key + Hash 字段，只读。
 */
public class ReservationRequest {

    public static final String ORDER_ID_FIELD = "orderId";
    public static final String USER_ID_FIELD = "userId";
    public static final String VOUCHER_ID_FIELD = "voucherId";
    public static final String RESERVED_AT_FIELD = "reservedAt";
    public static final String RETRY_COUNT_FIELD = "retryCount";
    public static final String LAST_RETRY_AT_FIELD = "lastRetryAt";

    private final String reservationKey;
    private final Map<Object, Object> fields;

    public ReservationRequest(String reservationKey, Map<Object, Object> fields) {
        this.reservationKey = reservationKey;
        this.fields = fields;
    }

    public String getReservationKey() {
        return reservationKey;
    }

    public Map<Object, Object> getFields() {
        return fields;
    }

    /** 读取字段并转为 Long；字段缺失或非数字时返回 null。 */
    public Long longField(String field) {
        Object value = fields.get(field);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
