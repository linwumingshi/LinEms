package com.energyx.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis-Plus 全局插件（租户隔离 + 分页）。
 *
 * <p>
 * <b>租户插件</b>：所有 SQL（含 BaseMapper 方法与注解 SQL）自动拼接 {@code tenant_id = 1} 条件； INSERT 自动填充
 * tenant_id 列。单租户环境 handler 固定返回 1，多租户接入后从 TenantContext 取值。 无 tenant_id
 * 列的表需加入忽略清单，否则插件拼接条件会报列不存在。
 * </p>
 *
 * <p>
 * <b>分页插件</b>：BaseMapper.selectPage / wrapper 分页能力（Page 对象）。
 * </p>
 *
 * <p>
 * {@code @ConditionalOnClass} 保证仅引入 mybatis-plus 的服务（业务模块）装配； 网关等 WebFlux 服务虽扫描
 * com.energyx 但无该依赖，不会加载。
 * </p>
 */
@Configuration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class MybatisPlusConfig {

	/** 无 tenant_id 列的表（租户插件忽略；新增表无该列必须补在此处） */
	private static final Set<String> IGNORE_TABLES = Set.of(
			// es_system：菜单/角色授权（无租户列）+ 租户表（tenant_id 是主键，不能被当前租户过滤）
			"sys_permission", "sys_role_permission", "sys_user_role", "sys_tenant",
			// es_device：凭证/分组/标签（跟随设备所属租户）
			"iot_device_certificate", "iot_device_group_relation", "iot_device_tag",
			// es_station / es_shadow / es_command：关联表
			"iot_station_device", "iot_shadow_history", "iot_command_ack");

	@Bean
	public MybatisPlusInterceptor mybatisPlusInterceptor() {
		MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
		// 租户行级隔离（单租户固定 1）
		interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
			@Override
			public Expression getTenantId() {
				return new LongValue(1L);
			}

			@Override
			public String getTenantIdColumn() {
				return "tenant_id";
			}

			@Override
			public boolean ignoreTable(String tableName) {
				return IGNORE_TABLES.contains(tableName);
			}
		}));
		// 分页（BaseMapper.selectPage 支持）
		interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
		return interceptor;
	}

}
