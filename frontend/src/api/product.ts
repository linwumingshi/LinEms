// frontend/src/api/product.ts
import http from './http'
import type { PageResult, Product, ProductSaveReq, ThingModelView } from '@/types/models'

/** 产品 API（网关 /api/product/** StripPrefix=1 → energy-product；分页参数 pageNum/pageSize） */
export const productApi = {
  page(params?: Record<string, unknown>): Promise<PageResult<Product>> {
    return http.get('/api/product/page', { params })
  },
  detail(productId: string): Promise<Product> {
    return http.get(`/api/product/${productId}`)
  },
  create(body: ProductSaveReq): Promise<string> {
    return http.post('/api/product', body)
  },
  update(productId: string, body: ProductSaveReq): Promise<void> {
    return http.put(`/api/product/${productId}`, body)
  },
  remove(productId: string): Promise<void> {
    return http.delete(`/api/product/${productId}`)
  },
  /** 物模型单版本视图；未发布后端返回业务错误（页面据此置空态） */
  thingModelGet(productId: string): Promise<ThingModelView> {
    return http.get(`/api/product/${productId}/thing-model`)
  },
  /** 发布/覆盖：同 version 覆盖并置当前，异 version 新增并切换当前 */
  thingModelSave(productId: string, body: { version: string; schemaJson: string }): Promise<ThingModelView> {
    return http.put(`/api/product/${productId}/thing-model`, body)
  },
}
