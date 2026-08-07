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

    /** 校验 JSON、发布物模型版本并置为当前生效，同步产品 model_version */
    ThingModelView saveThingModel(Long productId, ThingModelSaveReq req);
}
