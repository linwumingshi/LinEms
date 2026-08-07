package com.energyx.alarm.engine;

import com.energyx.alarm.model.AlarmCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警规则纯计算引擎测试（阈值比较 / 事件匹配 / 恢复条件 / 边界）。
 */
class AlarmRuleEngineTest {

    private static AlarmCondition cond(String metric, String op, Object value, Integer windowSec) {
        AlarmCondition c = new AlarmCondition();
        c.setMetric(metric);
        c.setOp(op);
        c.setValue(value);
        c.setWindowSec(windowSec);
        return c;
    }

    @Test
    @DisplayName("GTE：当前值 80 超阈 60")
    void gteMet() {
        assertTrue(AlarmRuleEngine.propertyMet(cond("temp", "GTE", 60, 60), 80));
    }

    @Test
    @DisplayName("GTE：当前值 50 未超阈 60")
    void gteNotMet() {
        assertFalse(AlarmRuleEngine.propertyMet(cond("temp", "GTE", 60, 60), 50));
    }

    @Test
    @DisplayName("LT：恢复条件 40 < 55 成立")
    void recoveryLtMet() {
        assertTrue(AlarmRuleEngine.recoveryMet(cond("temp", "LT", 55, null), 40));
    }

    @Test
    @DisplayName("字符串数值参与比较：当前 '80.5' 超阈 60")
    void stringNumericCompares() {
        assertTrue(AlarmRuleEngine.compare("GT", "80.5", 60));
    }

    @Test
    @DisplayName("非数值当前值：无法比较，返回 false")
    void nonNumericNotMet() {
        assertFalse(AlarmRuleEngine.compare("GT", "abc", 60));
    }

    @Test
    @DisplayName("EQ 数值相等：60 == 60.0")
    void eqNumeric() {
        assertTrue(AlarmRuleEngine.compare("EQ", 60, 60.0));
        assertFalse(AlarmRuleEngine.compare("EQ", 61, 60.0));
    }

    @Test
    @DisplayName("EQ 字符串相等 / NEQ 取反")
    void eqStringAndNeq() {
        assertTrue(AlarmRuleEngine.compare("EQ", "running", "running"));
        assertTrue(AlarmRuleEngine.compare("NEQ", "running", "stopped"));
        assertFalse(AlarmRuleEngine.compare("NEQ", "running", "running"));
    }

    @Test
    @DisplayName("未知 op / 缺值：返回 false")
    void unknownOpAndNull() {
        assertFalse(AlarmRuleEngine.compare("BETWEEN", 80, 60));
        assertFalse(AlarmRuleEngine.propertyMet(cond("temp", "GTE", 60, null), null));
    }

    @Test
    @DisplayName("事件匹配：标识相等触发，不等不触发")
    void eventMet() {
        AlarmCondition c = new AlarmCondition();
        c.setEvent("bmsFault");
        assertTrue(AlarmRuleEngine.eventMet(c, "bmsFault"));
        assertFalse(AlarmRuleEngine.eventMet(c, "overVolt"));
        assertFalse(AlarmRuleEngine.eventMet(new AlarmCondition(), "bmsFault"));
    }
}
