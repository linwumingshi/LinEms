package com.sanduo.energy.alarm.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 产品信息查询（跨 schema 读 es_product.iot_product，单 DataSource 全限定名）。
 *
 * <p>告警规则按 product_id 限定作用产品，而上报消息只携带 product_key；
 * 在此做 product_key → product_id 映射（本地带 TTL 缓存，见 AlarmService）。</p>
 */
@Mapper
public interface ProductInfoMapper {

    @Select("""
            SELECT product_id FROM es_product.iot_product
            WHERE product_key = #{productKey} AND deleted = 0
            LIMIT 1
            """)
    Long selectProductIdByKey(@Param("productKey") String productKey);
}
