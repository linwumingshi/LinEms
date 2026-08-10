package com.energyx.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.energyx.common.exception.BusinessException;
import com.energyx.common.exception.ErrorCode;
import com.energyx.common.tenant.TenantContext;
import com.energyx.product.entity.Product;
import com.energyx.product.entity.ThingModel;
import com.energyx.product.mapper.ProductMapper;
import com.energyx.product.mapper.ThingModelMapper;
import com.energyx.product.service.ProductService;
import com.energyx.product.util.ThingModelValidator;
import com.energyx.product.web.dto.ProductQuery;
import com.energyx.product.web.dto.ProductSaveReq;
import com.energyx.product.web.dto.ThingModelSaveReq;
import com.energyx.product.web.dto.ThingModelView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 产品与物模型服务实现。
 *
 * <p>
 * 租户隔离由条件化租户拦截器自动完成（HTTP 线程按 {@link TenantContext} 追加 tenant_id），
 * 本服务仅在校验时读取当前租户写入。物模型发布：同版本覆盖、异版本新增并切换 is_current。
 * </p>
 */
@Slf4j
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

	private final ThingModelMapper thingModelMapper;

	public ProductServiceImpl(ThingModelMapper thingModelMapper) {
		this.thingModelMapper = thingModelMapper;
	}

	@Override
	public Long create(ProductSaveReq req) {
		long tenantId = requireTenant();

		long dup = count(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, req.getProductKey()));
		if (dup > 0) {
			throw new BusinessException(ErrorCode.CONFLICT, "产品标识已存在：" + req.getProductKey());
		}

		Product product = new Product();
		BeanUtils.copyProperties(req, product);
		product.setTenantId(tenantId);
		if (product.getAuthType() == null || product.getAuthType().isBlank()) {
			product.setAuthType("SECRET");
		}
		if (product.getProtocol() == null || product.getProtocol().isBlank()) {
			product.setProtocol("MQTT");
		}
		if (product.getStatus() == null) {
			product.setStatus(1);
		}
		save(product);
		log.info("创建产品 productId={} key={} name={}", product.getProductId(), product.getProductKey(),
				product.getProductName());
		return product.getProductId();
	}

	@Override
	public void update(Long productId, ProductSaveReq req) {
		Product exists = requireProduct(productId);

		if (!Objects.equals(exists.getProductKey(), req.getProductKey())) {
			long dup = count(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, req.getProductKey())
				.ne(Product::getProductId, productId));
			if (dup > 0) {
				throw new BusinessException(ErrorCode.CONFLICT, "产品标识已存在：" + req.getProductKey());
			}
		}

		// modelVersion 由物模型发布维护，请求未传时不覆盖
		String oldModelVersion = exists.getModelVersion();
		BeanUtils.copyProperties(req, exists);
		if (req.getModelVersion() == null) {
			exists.setModelVersion(oldModelVersion);
		}
		updateById(exists);
		log.info("更新产品 productId={}", productId);
	}

	@Override
	public void delete(Long productId) {
		requireProduct(productId);
		removeById(productId); // 逻辑删除
		log.info("删除产品 productId={}", productId);
	}

	@Override
	public IPage<Product> page(ProductQuery query) {
		LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
			.eq(query.getDeviceType() != null && !query.getDeviceType().isBlank(), Product::getDeviceType,
					query.getDeviceType())
			.eq(query.getStatus() != null, Product::getStatus, query.getStatus())
			.and(query.getKeyword() != null && !query.getKeyword().isBlank(),
					w -> w.like(Product::getProductName, query.getKeyword())
						.or()
						.like(Product::getProductKey, query.getKeyword()))
			.orderByDesc(Product::getCreateTime);
		return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
	}

	@Override
	public Product detail(Long productId) {
		return requireProduct(productId);
	}

	@Override
	public ThingModelView getThingModel(Long productId) {
		requireProduct(productId);
		ThingModel model = thingModelMapper
			.selectOne(new LambdaQueryWrapper<ThingModel>().eq(ThingModel::getProductId, productId)
				.eq(ThingModel::getIsCurrent, 1));
		return model == null ? null : toView(model);
	}

	@Override
	public ThingModelView getThingModelByProductKey(String productKey) {
		if (productKey == null || productKey.isBlank()) {
			return null;
		}
		Product product = getBaseMapper()
			.selectOne(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, productKey));
		if (product == null) {
			return null;
		}
		// 不调 getThingModel(productId)：其内部 requireProduct 会再回源一次主键查询（且单测需 mock
		// selectById）。本路径已有 product，直接查当前生效物模型。
		ThingModel model = thingModelMapper
			.selectOne(new LambdaQueryWrapper<ThingModel>().eq(ThingModel::getProductId, product.getProductId())
				.eq(ThingModel::getIsCurrent, 1));
		return model == null ? null : toView(model);
	}

	@Override
	public ThingModelView saveThingModel(Long productId, ThingModelSaveReq req) {
		Product product = requireProduct(productId);
		ThingModelValidator.validate(req.getSchemaJson());

		ThingModel exist = thingModelMapper
			.selectOne(new LambdaQueryWrapper<ThingModel>().eq(ThingModel::getProductId, productId)
				.eq(ThingModel::getVersion, req.getVersion()));
		ThingModel model;
		if (exist != null) {
			exist.setSchemaJson(req.getSchemaJson());
			exist.setStatus(1);
			exist.setIsCurrent(1);
			thingModelMapper.updateById(exist);
			model = exist;
		}
		else {
			model = new ThingModel();
			model.setTenantId(product.getTenantId());
			model.setProductId(productId);
			model.setVersion(req.getVersion());
			model.setSchemaJson(req.getSchemaJson());
			model.setStatus(1);
			model.setIsCurrent(1);
			thingModelMapper.insert(model);
		}

		// 同产品其它版本取消当前生效；同步产品 model_version
		thingModelMapper.update(null,
				new LambdaUpdateWrapper<ThingModel>().eq(ThingModel::getProductId, productId)
					.ne(ThingModel::getModelId, model.getModelId())
					.set(ThingModel::getIsCurrent, 0));
		Product update = new Product();
		update.setProductId(productId);
		update.setModelVersion(req.getVersion());
		updateById(update);

		log.info("发布物模型 productId={} version={} modelId={}", productId, req.getVersion(), model.getModelId());
		return toView(model);
	}

	private Product requireProduct(Long productId) {
		Product product = getById(productId);
		if (product == null) {
			throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "产品不存在：" + productId);
		}
		return product;
	}

	private long requireTenant() {
		Long tenantId = TenantContext.getTenantId();
		if (tenantId == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少租户上下文");
		}
		return tenantId;
	}

	private ThingModelView toView(ThingModel model) {
		ThingModelView view = new ThingModelView();
		view.setModelId(model.getModelId());
		view.setProductId(model.getProductId());
		view.setVersion(model.getVersion());
		view.setSchemaJson(model.getSchemaJson());
		view.setStatus(model.getStatus());
		view.setIsCurrent(model.getIsCurrent());
		return view;
	}

}
