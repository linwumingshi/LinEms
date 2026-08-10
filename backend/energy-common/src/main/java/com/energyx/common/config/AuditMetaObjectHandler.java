package com.energyx.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：create_time / update_time / deleted。 对应
 * {@link com.energyx.common.entity.BaseEntity}。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

	@Override
	public void insertFill(MetaObject metaObject) {
		strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
		strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
		strictInsertFill(metaObject, "deleted", Integer.class, 0);
	}

	@Override
	public void updateFill(MetaObject metaObject) {
		strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
	}

}
