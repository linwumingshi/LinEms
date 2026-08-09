import type { ThingModelSchema } from '@/types/models'

/** 解析物模型 schema_json；任何失败返回空结构（页面显示空态而非崩溃） */
export function parseThingModel(schemaJson: string): ThingModelSchema {
  try {
    const raw = JSON.parse(schemaJson) as Partial<ThingModelSchema>
    return {
      properties: Array.isArray(raw.properties) ? raw.properties : [],
      services: Array.isArray(raw.services) ? raw.services : [],
      events: Array.isArray(raw.events) ? raw.events : [],
    }
  } catch {
    return { properties: [], services: [], events: [] }
  }
}
