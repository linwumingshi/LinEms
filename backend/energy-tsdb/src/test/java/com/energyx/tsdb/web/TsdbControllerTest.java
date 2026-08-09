package com.energyx.tsdb.web;

import com.energyx.common.exception.BusinessException;
import com.energyx.tsdb.service.TdengineQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class TsdbControllerTest {

    @Mock
    private TdengineQueryService queryService;

    private TsdbController controller;

    @BeforeEach
    void setup() {
        controller = new TsdbController(queryService);
    }

    @Test
    void emptyIdentifiers_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", " , ,", null, null, "desc", 1, 20));
    }

    @Test
    void tooManyIdentifiers_throwsParamInvalid() {
        String ids = "a,b,c,d,e,f,g,h,i,j,k";
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", ids, null, null, "desc", 1, 20));
    }

    @Test
    void sizeOutOfRange_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", null, null, "desc", 1, 0));
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", null, null, "desc", 1, 1001));
    }

    @Test
    void invalidOrder_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", null, null, "sideways", 1, 20));
    }

    @Test
    void invalidTimeRange_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "pk", "soc", 2000L, 1000L, "desc", 1, 20));
    }

    @Test
    void invalidProductKey_throwsParamInvalid() {
        assertThrows(BusinessException.class,
                () -> controller.propertyHistory("d", "bad key", "soc", null, null, "desc", 1, 20));
    }
}
