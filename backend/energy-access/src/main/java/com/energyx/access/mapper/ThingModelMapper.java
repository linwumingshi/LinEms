package com.energyx.access.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 物模型只读投影（读取产品当前生效版本 + schema_json）。
 * 缓存路径：cache:model:current:{pk}（L1 本地 + Redis）→ 本查询兜底。
 * <p>跨 schema 读 es_product.iot_product/iot_thing_model，单 DataSource 全限定名
 * （与 energy-alarm ProductInfoMapper / energy-command DeviceInfoMapper 同模式）。</p>
 */
@Mapper
public interface ThingModelMapper {

    @Select("""
            SELECT p.model_version, m.schema_json
            FROM es_product.iot_product p
            JOIN es_product.iot_thing_model m ON m.product_id = p.product_id AND m.is_current = 1
            WHERE p.product_key = #{productKey} AND p.status = 1 AND p.deleted = 0
            LIMIT 1
            """)
    ModelRow loadCurrentModel(@Param("productKey") String productKey);

    @Data
    class ModelRow {
        private String modelVersion;
        private String schemaJson;
    }
}
