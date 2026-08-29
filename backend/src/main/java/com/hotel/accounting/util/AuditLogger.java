package com.hotel.accounting.util;

import com.hotel.accounting.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计日志：金额相关操作（谁/何时/改了什么金额）写入 logs/audit-日期.log。
 */
@Component
public class AuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger("AUDIT");

    public void log(String action, String detail) {
        LOG.info("user={} action={} detail={}", UserContext.username(), action, detail);
    }

    public void logAmount(String action, String target, String amountChanged) {
        LOG.info("user={} action={} target={} amount={}", UserContext.username(), action, target, amountChanged);
    }
}
