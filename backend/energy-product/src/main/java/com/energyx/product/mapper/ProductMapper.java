package com.energyx.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.energyx.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * 产品 Mapper。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

}
