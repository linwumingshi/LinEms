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
 * <p>
 * 提供产品（{@link com.energyx.product.entity.Product}）的增删改查，以及物模型
 * （{@link com.energyx.product.web.dto.ThingModelView}）的发布与查询能力。
 * </p>
 *
 * <ul>
 * <li>POST /api/product 创建产品；</li>
 * <li>GET /api/product/page 分页查询产品；</li>
 * <li>GET /api/product/by-key 按 productKey 查询产品ID；</li>
 * <li>GET /api/product/{productId} 产品详情；</li>
 * <li>PUT /api/product/{productId} 更新产品；</li>
 * <li>DELETE /api/product/{productId} 逻辑删除产品；</li>
 * <li>GET /api/product/{productId}/thing-model 当前生效物模型；</li>
 * <li>GET /api/product/thing-model/by-key 按 productKey 查询当前生效物模型；</li>
 * <li>PUT /api/product/{productId}/thing-model 发布物模型并置为当前生效。</li>
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

	/**
	 * 创建产品。
	 *
	 * <p>
	 * 校验 productKey 全局唯一后写入产品记录，并返回自增主键 productId。
	 * </p>
	 * @param req 产品创建请求体，字段说明见 {@link com.energyx.product.web.dto.ProductSaveReq}
	 * @return {@link com.energyx.common.model.Result}<{@link java.lang.Long}> 创建成功的产品ID
	 */
	@PostMapping
	public Result<Long> create(@Valid @RequestBody ProductSaveReq req) {
		return Result.ok(productService.create(req));
	}

	/**
	 * 分页查询产品。
	 *
	 * <p>
	 * 按租户范围（由租户上下文注入）分页返回产品列表，支持设备类型、状态及关键字模糊匹配。
	 * </p>
	 * @param query 分页/筛选条件，字段说明见 {@link com.energyx.product.web.dto.ProductQuery}
	 * @return {@link com.energyx.common.model.Result}<{@link com.energyx.common.model.PageResult}<{@link com.energyx.product.entity.Product}>>
	 * 分页结果
	 */
	@GetMapping("/page")
	public Result<PageResult<Product>> page(ProductQuery query) {
		return Result.ok(PageResult.of(productService.page(query)));
	}

	/**
	 * 按 productKey 查询产品ID。
	 *
	 * <p>
	 * 供跨服务调用方（如告警、规则引擎）将设备 productKey 映射为 productId；产品不存在时返回 null。
	 * </p>
	 * @param productKey 产品标识（来源：查询参数）
	 * @return {@link com.energyx.common.model.Result}<{@link java.lang.Long}> 产品ID，不存在时返回
	 * null
	 */
	@GetMapping("/by-key")
	public Result<Long> productIdByKey(@RequestParam String productKey) {
		return Result.ok(productService.findIdByKey(productKey));
	}

	/**
	 * 查询产品详情。
	 * @param productId 产品ID（来源：路径变量）
	 * @return {@link com.energyx.common.model.Result}<{@link com.energyx.product.entity.Product}>
	 * 产品详情
	 */
	@GetMapping("/{productId}")
	public Result<Product> detail(@PathVariable Long productId) {
		return Result.ok(productService.detail(productId));
	}

	/**
	 * 更新产品。
	 *
	 * <p>
	 * 按 productId 更新产品信息；请求体中未传的 modelVersion 不会被覆盖。
	 * </p>
	 * @param productId 产品ID（来源：路径变量）
	 * @param req 产品更新请求体，字段说明见 {@link com.energyx.product.web.dto.ProductSaveReq}
	 * @return {@link com.energyx.common.model.Result}<{@link java.lang.Void}> 更新成功
	 */
	@PutMapping("/{productId}")
	public Result<Void> update(@PathVariable Long productId, @Valid @RequestBody ProductSaveReq req) {
		productService.update(productId, req);
		return Result.ok();
	}

	/**
	 * 逻辑删除产品。
	 * @param productId 产品ID（来源：路径变量）
	 * @return {@link com.energyx.common.model.Result}<{@link java.lang.Void}> 删除成功
	 */
	@DeleteMapping("/{productId}")
	public Result<Void> delete(@PathVariable Long productId) {
		productService.delete(productId);
		return Result.ok();
	}

	/**
	 * 查询产品当前生效物模型。
	 *
	 * <p>
	 * 返回该产品已发布且当前生效的物模型视图；若未发布物模型则返回 NOT_FOUND 错误。
	 * </p>
	 * @param productId 产品ID（来源：路径变量）
	 * @return {@link com.energyx.common.model.Result}<{@link com.energyx.product.web.dto.ThingModelView}>
	 * 当前生效物模型；未发布时返回错误
	 */
	@GetMapping("/{productId}/thing-model")
	public Result<ThingModelView> getThingModel(@PathVariable Long productId) {
		ThingModelView view = productService.getThingModel(productId);
		return view == null ? Result.fail(ErrorCode.NOT_FOUND, "产品未发布物模型：" + productId) : Result.ok(view);
	}

	/**
	 * 按 productKey 查询当前生效物模型。
	 *
	 * <p>
	 * 供跨服务调用方按 productKey 获取物模型；产品不存在或未发布物模型时返回 NOT_FOUND 错误。
	 * </p>
	 * @param productKey 产品标识（来源：查询参数）
	 * @return {@link com.energyx.common.model.Result}<{@link com.energyx.product.web.dto.ThingModelView}>
	 * 当前生效物模型；未发布时返回错误
	 */
	@GetMapping("/thing-model/by-key")
	public Result<ThingModelView> getThingModelByKey(@RequestParam("productKey") String productKey) {
		ThingModelView view = productService.getThingModelByProductKey(productKey);
		return view == null ? Result.fail(ErrorCode.NOT_FOUND, "产品未发布物模型或不存在：" + productKey) : Result.ok(view);
	}

	/**
	 * 发布物模型并置为当前生效。
	 *
	 * <p>
	 * 校验版本（{@code version}）与物模型 JSON（{@code schemaJson}）后保存，并自动将该版本置为产品的当前生效物模型。
	 * </p>
	 * @param productId 产品ID（来源：路径变量）
	 * @param req 物模型发布请求体，字段说明见 {@link com.energyx.product.web.dto.ThingModelSaveReq}
	 * @return {@link com.energyx.common.model.Result}<{@link com.energyx.product.web.dto.ThingModelView}>
	 * 发布后的物模型视图
	 */
	@PutMapping("/{productId}/thing-model")
	public Result<ThingModelView> saveThingModel(@PathVariable Long productId,
			@Valid @RequestBody ThingModelSaveReq req) {
		return Result.ok(productService.saveThingModel(productId, req));
	}

}
