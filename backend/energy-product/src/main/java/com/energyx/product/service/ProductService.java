package com.energyx.product.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.energyx.product.entity.Product;
import com.energyx.product.web.dto.ProductQuery;
import com.energyx.product.web.dto.ProductSaveReq;
import com.energyx.product.web.dto.ThingModelSaveReq;
import com.energyx.product.web.dto.ThingModelView;

/**
 * 产品与物模型服务。
 */
public interface ProductService {

	Long create(ProductSaveReq req);

	void update(Long productId, ProductSaveReq req);

	void delete(Long productId);

	IPage<Product> page(ProductQuery query);

	Product detail(Long productId);

	/** 当前生效物模型；未发布返回 null */
	ThingModelView getThingModel(Long productId);

	/** 按 productKey 查当前生效物模型（设备仅有 productKey）；产品不存在或未发布返回 null */
	ThingModelView getThingModelByProductKey(String productKey);

	/** 按 productKey 查产品ID（供跨服务调用方映射 product_key → product_id）；不存在返回 null */
	Long findIdByKey(String productKey);

	/** 校验 JSON、发布物模型版本并置为当前生效，同步产品 model_version */
	ThingModelView saveThingModel(Long productId, ThingModelSaveReq req);

}
