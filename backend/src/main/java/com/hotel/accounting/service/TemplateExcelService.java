package com.hotel.accounting.service;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 「月度记账模板.xlsx」两 Sheet 生成：
 * ①当月成本 ②当月销售利润（路客云订单式）。
 * 每日房态不再单独填——由『当月销售利润』订单推导（已排房=是 + 房间号，见 excel_parser._parse_lukeyun_sheet）。
 * 下载用，不参与解析。
 */
@Service
public class TemplateExcelService {

    /** 类型列已移除：归类由导入时 AI 建议自动完成（SC-03），无需用户在模板里填写。 */
    private static final String[] ROW_HEADERS = {"序号", "费用项目", "金额（元）", "备注"};
    /** ②当月销售利润 = 路客云订单导出可直接使用（30 列，与路客云真实导出逐字节一致，含表头内换行）。 */
    private static final String[] LK_HEADERS = {
            "房费(含佣)", "佣金", "房费(减佣)", "其他消费", "订单总收入\n(房费(含佣)+其他消费)", "订单总收入(减佣)\n（房费(减佣)+其他消费）",
            "押金", "金额备注", "支付方式", "订单编号(路客云)", "订单编号(平台)", "订单来源+渠道账号",
            "预订人", "手机号", "房型", "房型分组", "房间", "入住时间", "离店时间", "入住天数(间夜数)",
            "入住人数", "入住状态", "预定时间", "订单标记", "订单备注", "说明", "金额分摊模式",
            "占库存", "已排房", "计入统计"};
    public byte[] build() {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            costSheet(wb);
            salesSheet(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成模板失败", e);
        }
    }

    private void costSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("当月成本");
        header(wb, sheet, 3, ROW_HEADERS);
        for (int i = 0; i < 12; i++) {
            Row r = sheet.createRow(4 + i);
            r.createCell(0).setCellValue(i + 1);
            r.createCell(2).setCellValue(0);
            r.createCell(3).setCellValue("");
        }
        sheet.setColumnWidth(0, 6 * 256);
        sheet.setColumnWidth(1, 22 * 256);
        sheet.setColumnWidth(2, 14 * 256);
        sheet.setColumnWidth(3, 20 * 256);
    }

    /** ②订单明细 = 与路客云后台导出结构一模一样（Sheet 名/横幅行/表头行位置/30 列），可直接粘贴或整表覆盖。 */
    private void salesSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("订单明细");
        noteRow(wb, sheet, 0, "*金额按天计算，由筛选时段决定。订单原金额可见\"金额备注\"", false);
        header(wb, sheet, 1, LK_HEADERS);
        for (int i = 0; i < 24; i++) {
            sheet.createRow(2 + i);
        }
        for (int i = 0; i < LK_HEADERS.length; i++) {
            sheet.setColumnWidth(i, 14 * 256);
        }
        sheet.setColumnWidth(11, 18 * 256); // 订单来源+渠道账号
        sheet.setColumnWidth(17, 18 * 256); // 入住时间
        sheet.setColumnWidth(18, 18 * 256); // 离店时间
        sheet.setColumnWidth(19, 16 * 256); // 入住天数(间夜数)
    }

    private void header(Workbook wb, Sheet sheet, int rowIdx, String[] headers) {
        header(wb, sheet, rowIdx, headers, null);
    }

    private void header(Workbook wb, Sheet sheet, int rowIdx, String[] headers, int[] privacyCols) {
        Row row = sheet.createRow(rowIdx);
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        CellStyle privacy = null;
        if (privacyCols != null && privacyCols.length > 0) {
            privacy = wb.createCellStyle();
            privacy.setFont(font);
            privacy.setAlignment(HorizontalAlignment.CENTER);
            privacy.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            privacy.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        for (int i = 0; i < headers.length; i++) {
            var cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            boolean isPrivacy = false;
            if (privacyCols != null) {
                for (int c : privacyCols) {
                    if (c == i) {
                        isPrivacy = true;
                        break;
                    }
                }
            }
            cell.setCellStyle(isPrivacy ? privacy : style);
        }
    }

    private void noteRow(Workbook wb, Sheet sheet, int rowIdx, String text, boolean bold) {
        Row row = sheet.createRow(rowIdx);
        var cell = row.createCell(0);
        cell.setCellValue(text);
        CellStyle style = wb.createCellStyle();
        if (bold) {
            Font font = wb.createFont();
            font.setBold(true);
            style.setFont(font);
        }
        cell.setCellStyle(style);
    }
}
