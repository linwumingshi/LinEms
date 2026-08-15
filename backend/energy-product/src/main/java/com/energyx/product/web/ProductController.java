package com.energyx.product.web;

import com.energyx.common.exception.ErrorCode;
import com.energyx.common.model.PageResult;
import com.energyx.common.model.Result;
import com.energyx.product.entity.Product;
import com.energyx.product.service.ProductService;
import com.energyx.product.web.dto.ProductQuery;
import com.energyx.product.web.dto.ProductSaveReq;
import com.energyx.product.web.dto.ThingModelSaveReq;
import com.energyx.product.web.dto.ThingModelView;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品与物模型 API。
 *
 * <ul>
 * <li>POST /api/product 创建产品；</li>
 * <li>GET /api/product/page 分页查询；</li>
 * <li>GET /api/product/{id} 产品详情；</li>
 * <li>PUT /api/product/{id} 更新产品；</li>
 * <li>DELETE /api/product/{id} 逻辑删除；</li>
 * <li>GET /api/product/{id}/thing-model 当前生效物模型；</li>
 * <li>PUT /api/product/{id}/thing-model 发布物模型并置为当前生效。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/product")
public class ProductController {

	private final ProductService productService;

	public ProductController(ProductService productService) {
		this.productService = productService;
	}

	@PostMapping
	public Result<Long> create(@Valid @RequestBody ProductSaveReq req) {
		return Result.ok(productService.create(req));
	}

	@GetMapping("/page")
	public Result<PageResult<Product>> page(ProductQuery query) {
		return Result.ok(PageResult.of(productService.page(query)));
	}

	/** 按 productKey 查产品ID（跨服务调用方：告警/规则按 key 映射 product_id；不存在返回 null） */
	@GetMapping("/by-key")
	public Result<Long> productIdByKey(@RequestParam String productKey) {
		return Result.ok(productService.findIdByKey(productKey));
	}

	@GetMapping("/{productId}")
	public Result<Product> detail(@PathVariable Long productId) {
		return Result.ok(productService.detail(productId));
	}

	@PutMapping("/{productId}")
	public Result<Void> update(@PathVariable Long productId, @Valid @RequestBody ProductSaveReq req) {
		productService.update(productId, req);
		return Result.ok();
	}

	@DeleteMapping("/{productId}")
	public Result<Void> delete(@PathVariable Long productId) {
		productService.delete(productId);
		return Result.ok();
	}

	@GetMapping("/{productId}/thing-model")
	public Result<ThingModelView> getThingModel(@PathVariable Long productId) {
		ThingModelView view = productService.getThingModel(productId);
		return view == null ? Result.fail(ErrorCode.NOT_FOUND, "产品未发布物模型：" + productId) : Result.ok(view);
	}

	@GetMapping("/thing-model/by-key")
	public Result<ThingModelView> getThingModelByKey(@RequestParam("productKey") String productKey) {
		ThingModelView view = productService.getThingModelByProductKey(productKey);
		return view == null ? Result.fail(ErrorCode.NOT_FOUND, "产品未发布物模型或不存在：" + productKey) : Result.ok(view);
	}

	@PutMapping("/{productId}/thing-model")
	public Result<ThingModelView> saveThingModel(@PathVariable Long productId,
			@Valid @RequestBody ThingModelSaveReq req) {
		return Result.ok(productService.saveThingModel(productId, req));
	}

}
