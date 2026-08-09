package com.energyx.product.service.impl;

import com.energyx.product.entity.Product;
import com.energyx.product.entity.ThingModel;
import com.energyx.product.mapper.ProductMapper;
import com.energyx.product.mapper.ThingModelMapper;
import com.energyx.product.web.dto.ThingModelView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ThingModelMapper thingModelMapper;

    private ProductServiceImpl service;

    @BeforeEach
    void setup() {
        service = new ProductServiceImpl(thingModelMapper);
        // ServiceImpl 的 baseMapper 由 MyBatis-Plus 运行期注入，单测里反射塞 mock
        ReflectionTestUtils.setField(service, "baseMapper", productMapper);
    }

    @Test
    void byKey_productNotFound_returnsNull() {
        when(productMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getThingModelByProductKey("no-such-key"));
    }

    @Test
    void byKey_noCurrentModel_returnsNull() {
        Product p = new Product();
        p.setProductId(1L);
        when(productMapper.selectOne(any())).thenReturn(p);
        when(thingModelMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getThingModelByProductKey("snd_ess_pcs"));
    }

    @Test
    void byKey_found_returnsView() {
        Product p = new Product();
        p.setProductId(1L);
        p.setProductKey("snd_ess_pcs");
        ThingModel m = new ThingModel();
        m.setModelId(9L);
        m.setProductId(1L);
        m.setVersion("v1");
        m.setSchemaJson("{}");
        m.setStatus(1);
        m.setIsCurrent(1);
        when(productMapper.selectOne(any())).thenReturn(p);
        when(thingModelMapper.selectOne(any())).thenReturn(m);

        ThingModelView view = service.getThingModelByProductKey("snd_ess_pcs");
        assertEquals("v1", view.getVersion());
        assertEquals(1L, view.getProductId().longValue());
    }

    @Test
    void byKey_blank_returnsNull() {
        assertNull(service.getThingModelByProductKey(""));
        assertNull(service.getThingModelByProductKey(null));
    }
}
