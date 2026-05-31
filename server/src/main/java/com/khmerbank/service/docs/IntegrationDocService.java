package com.khmerbank.service.docs;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.AreaBreak;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.properties.AreaBreakType;
import com.itextpdf.layout.properties.HorizontalAlignment;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;

/**
 * Generates a per-user PDF integration guide showing the API key + the
 * endpoints, with copy-paste code samples in Python, curl and JS.
 */
@Service
public class IntegrationDocService {

    @Value("${app.public-base-url:http://localhost:8080}")
    private String baseUrl;

    private static final DeviceRgb BRAND      = new DeviceRgb(37, 53, 245);
    private static final DeviceRgb BRAND_DARK = new DeviceRgb(28, 37, 168);
    private static final DeviceRgb INK_900    = new DeviceRgb(22, 26, 35);
    private static final DeviceRgb INK_500    = new DeviceRgb(100, 110, 128);
    private static final DeviceRgb INK_100    = new DeviceRgb(238, 240, 244);
    private static final DeviceRgb ACCENT     = new DeviceRgb(14, 207, 129);
    private static final DeviceRgb INK_950    = new DeviceRgb(10, 13, 20);

    /** KHQR brand red — the exact rgb used in the live card and bot. */
    private static final DeviceRgb KHQR_RED   = new DeviceRgb(226, 26, 26);
    /** Dashed tear-line grey — matches `.dashed-line` in the dashboard. */
    private static final DeviceRgb TEAR_GREY  = new DeviceRgb(128, 128, 128);
    /** Soft "scan with…" footnote. */
    private static final DeviceRgb INK_400    = new DeviceRgb(150, 158, 175);

    public byte[] generate(String userName, String email, String apiKey) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            pdf.setDefaultPageSize(PageSize.A4);
            Document doc = new Document(pdf);
            doc.setMargins(36, 36, 36, 36);

            PdfFont sans = PdfFontFactory.createFont("Helvetica");
            PdfFont sansBold = PdfFontFactory.createFont("Helvetica-Bold");
            PdfFont mono = PdfFontFactory.createFont("Courier");

            /* ── Header ───────────────────────────────────────────── */
            Table header = new Table(UnitValue.createPercentArray(new float[]{1, 3}))
                    .setWidth(UnitValue.createPercentValue(100))
                    .setMarginBottom(18);
            header.addCell(new Cell().add(
                    new Paragraph("KhmerBank")
                            .setFont(sansBold).setFontSize(18).setFontColor(BRAND))
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
            header.addCell(new Cell().add(
                    new Paragraph("Payment Gateway API · Integration Guide")
                            .setFont(sans).setFontSize(10).setFontColor(INK_500)
                            .setTextAlignment(TextAlignment.RIGHT))
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
            doc.add(header);

            doc.add(new Paragraph("Welcome, " + (userName == null || userName.isBlank() ? "developer" : userName))
                    .setFont(sansBold).setFontSize(22).setFontColor(INK_900).setMarginTop(0));
            doc.add(new Paragraph("Generated " + LocalDate.now())
                    .setFont(sans).setFontSize(9).setFontColor(INK_500));

            /* ── API key card ─────────────────────────────────────── */
            Table keyCard = new Table(UnitValue.createPercentArray(new float[]{1}))
                    .setWidth(UnitValue.createPercentValue(100))
                    .setMarginTop(18);
            Cell box = new Cell()
                    .setBackgroundColor(INK_950)
                    .setPadding(14)
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            box.add(new Paragraph("YOUR API KEY")
                    .setFont(sansBold).setFontSize(8)
                    .setFontColor(new DeviceRgb(150, 200, 255))
                    .setMarginBottom(6));
            box.add(new Paragraph(apiKey == null ? "(missing)" : apiKey)
                    .setFont(mono).setFontSize(11)
                    .setFontColor(ACCENT)
                    .setMarginBottom(8));
            box.add(new Paragraph("Use this as the X-API-Key header in your requests.")
                    .setFont(sans).setFontSize(8).setFontColor(new DeviceRgb(180, 190, 215)));
            keyCard.addCell(box);
            doc.add(keyCard);

            /* ── Endpoints summary table ──────────────────────────── */
            section(doc, sansBold, "Endpoints");
            Table endpoints = new Table(UnitValue.createPercentArray(new float[]{1, 3}))
                    .setWidth(UnitValue.createPercentValue(100));
            endpoints.addHeaderCell(headerCell("Method", sansBold));
            endpoints.addHeaderCell(headerCell("Path",   sansBold));
            row(endpoints, sans, mono, "POST", "/api/v1/payments/qr");
            row(endpoints, sans, mono, "GET",  "/api/v1/payments/{transactionId}/status");
            row(endpoints, sans, mono, "GET",  "/api/v1/merchants");
            row(endpoints, sans, mono, "POST", "/api/v1/merchants/upload");
            doc.add(endpoints);

            /* ── Quick start (Python) ─────────────────────────────── */
            section(doc, sansBold, "Python (with KhmerBank SDK)");
            doc.add(codeBlock(mono,
                    "from khmerbank import KhmerBank, BankType, Currency\n\n" +
                    "client = KhmerBank(api_key=\"" + nullSafe(apiKey) + "\",\n" +
                    "                   base_url=\"" + baseUrl + "\")\n\n" +
                    "qr = client.generate_qr(\n" +
                    "    bank=BankType.BAKONG,\n" +
                    "    amount=\"1.50\",\n" +
                    "    currency=Currency.USD,\n" +
                    "    description=\"Order #1234\",\n" +
                    ")\n" +
                    "print(qr.qr_payload)\n" +
                    "print(qr.qr_image)   # data:image/png;base64,...\n\n" +
                    "status = client.wait_for_payment(qr.transaction_id, timeout=900)\n" +
                    "print(\"Paid:\", status.paid)"));

            /* ── curl ─────────────────────────────────────────────── */
            section(doc, sansBold, "cURL");
            doc.add(codeBlock(mono,
                    "curl -X POST " + baseUrl + "/api/v1/payments/qr \\\n" +
                    "  -H \"X-API-Key: " + nullSafe(apiKey) + "\" \\\n" +
                    "  -H \"Content-Type: application/json\" \\\n" +
                    "  -d '{\"bankType\":\"BAKONG\",\"amount\":1.5,\"currency\":\"USD\"}'\n\n" +
                    "curl \"" + baseUrl + "/api/v1/payments/$TXN/status\" \\\n" +
                    "  -H \"X-API-Key: " + nullSafe(apiKey) + "\""));

            /* ── JavaScript ───────────────────────────────────────── */
            section(doc, sansBold, "JavaScript (fetch)");
            doc.add(codeBlock(mono,
                    "const KEY = \"" + nullSafe(apiKey) + "\";\n" +
                    "const BASE = \"" + baseUrl + "\";\n\n" +
                    "const r = await fetch(BASE + \"/api/v1/payments/qr\", {\n" +
                    "  method: 'POST',\n" +
                    "  headers: {\n" +
                    "    'Content-Type': 'application/json',\n" +
                    "    'X-API-Key': KEY,\n" +
                    "  },\n" +
                    "  body: JSON.stringify({\n" +
                    "    bankType: 'BAKONG', amount: 1.5, currency: 'USD',\n" +
                    "  }),\n" +
                    "});\n" +
                    "const { data } = await r.json();\n" +
                    "console.log(data.qrPayload, data.md5);"));

            /* ── KHQR Receipt Card — Design & Code (page 4) ─────── */
            khqrCardSection(doc, sans, sansBold, mono);

            /* ── Footer ───────────────────────────────────────────── */
            doc.add(new Paragraph("\n— Need help? Reply to this email or contact support@khmerbank.dev")
                    .setFont(sans).setFontSize(9).setFontColor(INK_500)
                    .setMarginTop(16));

            doc.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("PDF generation failed", e);
        }
    }

    /* ── helpers ─────────────────────────────────────────────────── */

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static void section(Document d, PdfFont font, String title) {
        d.add(new Paragraph(title.toUpperCase())
                .setFont(font).setFontSize(9).setFontColor(BRAND_DARK)
                .setMarginTop(16).setMarginBottom(4)
                .setCharacterSpacing(1.2f));
    }

    private Cell headerCell(String text, PdfFont bold) {
        return new Cell().add(
                new Paragraph(text).setFont(bold).setFontSize(9).setFontColor(INK_500)
                        .setCharacterSpacing(0.6f))
                .setBackgroundColor(INK_100)
                .setPadding(6)
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
    }

    private void row(Table t, PdfFont sans, PdfFont mono, String method, String path) {
        t.addCell(new Cell()
                .add(new Paragraph(method).setFont(sans).setFontSize(9).setFontColor(BRAND))
                .setPadding(6)
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
        t.addCell(new Cell()
                .add(new Paragraph(path).setFont(mono).setFontSize(9).setFontColor(INK_900))
                .setPadding(6)
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
    }

    private Table codeBlock(PdfFont mono, String code) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{1}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginTop(4);
        t.addCell(new Cell()
                .setBackgroundColor(INK_950)
                .setPadding(10)
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .add(new Paragraph(code)
                        .setFont(mono).setFontSize(8.5f)
                        .setFontColor(ColorConstants.WHITE)));
        return t;
    }

    /* ── KHQR Receipt Card section (page 4) ───────────────────────── */

    /**
     * The "KHQR Receipt Card — Design & Code" section. Lives on its own
     * page so a developer can flick straight to it after skimming the
     * endpoints / code samples.
     *
     * <p>What's here, in order:
     * <ol>
     *   <li>A faithful preview of the receipt card drawn directly on the
     *       PDF canvas (red header, white "KHQR" wordmark, red triangle
     *       notch, dashed tear line, merchant + amount, QR placeholder).
     *       The preview is a vector — not a raster — so it stays crisp at
     *       any zoom in any PDF reader.</li>
     *   <li>The selectable TSX, CSS, official KHQR SVG, and plain-HTML
     *       fallback code blocks (text, not images, so users can
     *       copy-paste straight from the PDF).</li>
     *   <li>A short integration tip about always using the {@code qr_image}
     *       PNG returned by {@code /api/generate_qr} instead of regenerating
     *       client-side.</li>
     * </ol>
     */
    private void khqrCardSection(Document doc, PdfFont sans, PdfFont sansBold, PdfFont mono) {
        // Force a fresh page so the section header always lands at the top.
        doc.add(new AreaBreak(AreaBreakType.NEXT_PAGE));

        doc.add(new Paragraph("KHQR Receipt Card — Design & Code")
                .setFont(sansBold).setFontSize(16).setFontColor(INK_900)
                .setMarginBottom(2));
        doc.add(new Paragraph(
                "This is the receipt card every customer sees. Drop the HTML " +
                "below into your project to match it exactly — the QR area " +
                "binds to the qr_image PNG returned by /api/generate_qr.")
                .setFont(sans).setFontSize(9).setFontColor(INK_500)
                .setMarginBottom(12));

        // ── 1. Vector preview, drawn directly on the page canvas ──
        // We embed the preview inside a Cell whose `next renderer` paints
        // the card via PdfCanvas — that way iText still flows the rest of
        // the section below it and we don't have to compute coordinates by
        // hand against the page geometry.
        Table previewWrap = new Table(UnitValue.createPercentArray(new float[]{1}))
                .setWidth(UnitValue.createPercentValue(100))
                .setMarginBottom(14);
        Cell previewCell = new Cell()
                .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)
                .setHeight(420f)
                .setHorizontalAlignment(HorizontalAlignment.CENTER)
                .setTextAlignment(TextAlignment.CENTER);
        // Empty paragraph so the cell has measurable layout content; the
        // actual drawing happens in the renderer.
        previewCell.add(new Paragraph(""));
        previewCell.setNextRenderer(new KhqrCardRenderer(previewCell, sansBold, sans));
        previewWrap.addCell(previewCell);
        doc.add(previewWrap);

        doc.add(new Paragraph("Sample preview · live card binds to the user's QR at runtime")
                .setFont(sans).setFontSize(8).setFontColor(INK_500)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(14));

        // ── 2. Integration tip (renders before the code so devs read it
        //      before they paste anything) ──
        doc.add(new Paragraph("Integration tip")
                .setFont(sansBold).setFontSize(9).setFontColor(BRAND_DARK)
                .setCharacterSpacing(1.2f)
                .setMarginTop(4).setMarginBottom(4));
        doc.add(new Paragraph(
                "Use the qr_image PNG returned by /api/generate_qr in the " +
                "<img/> slot — never regenerate the QR client-side or the " +
                "MD5 won't match what /api/check_payment expects.")
                .setFont(sans).setFontSize(9).setFontColor(INK_900)
                .setMarginBottom(10));

        // ── 3. TSX / React component (selectable text) ──
        section(doc, sansBold, "React + Tailwind component (TSX)");
        doc.add(codeBlock(mono, KHQR_TSX_SNIPPET));

        // ── 4. globals.css — the four utility classes ──
        section(doc, sansBold, "globals.css — required custom classes");
        doc.add(codeBlock(mono, KHQR_CSS_SNIPPET));

        // ── 5. Official white "KHQR" wordmark SVG (verbatim) ──
        section(doc, sansBold, "Official KHQR wordmark — verbatim SVG (60 × 14)");
        doc.add(codeBlock(mono, KHQR_LOGO_SVG_SNIPPET));

        // ── 6. Plain HTML fallback for projects without React/Tailwind ──
        section(doc, sansBold, "Plain HTML fallback (no React, no Tailwind)");
        doc.add(codeBlock(mono, KHQR_PLAIN_HTML_SNIPPET));
    }

    /**
     * iText cell renderer that draws the KHQR receipt card preview on the
     * PDF canvas. Coordinates use iText's {@code points} (1pt = 1/72 inch);
     * the live card is 273 × 396 CSS pixels — we keep that 1:1 in points
     * since it gives a comfortable size on an A4 page.
     */
    private static final class KhqrCardRenderer
            extends com.itextpdf.layout.renderer.CellRenderer {

        private final PdfFont sansBold;
        private final PdfFont sans;

        KhqrCardRenderer(Cell modelElement, PdfFont sansBold, PdfFont sans) {
            super(modelElement);
            this.sansBold = sansBold;
            this.sans = sans;
        }

        @Override
        public com.itextpdf.layout.renderer.IRenderer getNextRenderer() {
            return new KhqrCardRenderer((Cell) getModelElement(), sansBold, sans);
        }

        @Override
        public void draw(com.itextpdf.layout.renderer.DrawContext drawContext) {
            super.draw(drawContext);
            PdfCanvas c = drawContext.getCanvas();
            com.itextpdf.kernel.geom.Rectangle box = getOccupiedAreaBBox();

            // Card geometry — fixed 273 × 396, centered horizontally inside
            // the cell so the section looks balanced regardless of page
            // margins.
            float W = 273f, H = 396f;
            float x = box.getX() + (box.getWidth() - W) / 2f;
            float y = box.getY() + (box.getHeight() - H) / 2f;

            float radius   = 22f;     // rounded corners on the outer card
            float headerH  = 42.6562f; // exact match to the live red bar
            float notch    = 19.5f;    // red triangle notch (top-right)
            float qrSize   = 195f;
            float merchPad = 15f;

            // Outer card — white background, rounded corners, soft 1pt
            // grey ring (mimics the .qr-card box-shadow).
            c.saveState()
             .setFillColor(ColorConstants.WHITE)
             .setStrokeColor(new DeviceRgb(228, 230, 235))
             .setLineWidth(0.6f)
             .roundRectangle(x, y, W, H, radius)
             .fillStroke()
             .restoreState();

            // Red header bar — rounded only at the top, square at the
            // bottom so it sits flush with the dashed tear line.
            c.saveState()
             .setFillColor(KHQR_RED)
             .roundRectangle(x, y + H - headerH, W, headerH, radius)
             .fill()
             .restoreState();
            // Square off the bottom of the header (the rounded rect rounds
            // all four corners; we paint a thin red strip at the bottom of
            // the header to flatten the bottom corners visually).
            c.saveState()
             .setFillColor(KHQR_RED)
             .rectangle(x, y + H - headerH, W, radius)
             .fill()
             .restoreState();

            // White "KHQR" wordmark — typographic stand-in for the SVG.
            // The selectable code block below the preview includes the
            // actual 8-path SVG, so devs can copy-paste the real thing into
            // their project.
            String mark = "KHQR";
            float markFontSize = 14f;
            float markWidth = sansBold.getWidth(mark, markFontSize);
            c.saveState()
             .beginText()
             .setFontAndSize(sansBold, markFontSize)
             .setFillColor(ColorConstants.WHITE)
             .moveText(x + (W - markWidth) / 2f,
                       y + H - headerH + (headerH - markFontSize) / 2f + 1.5f)
             .showText(mark)
             .endText()
             .restoreState();

            // Red triangle notch in the top-right, just under the header.
            float triTop = y + H - headerH;
            c.saveState()
             .setFillColor(KHQR_RED)
             .moveTo(x + W,         triTop)
             .lineTo(x + W,         triTop - notch)
             .lineTo(x + W - notch, triTop)
             .closePath()
             .fill()
             .restoreState();

            // Merchant name + amount block. Lives in the top "ticket" area
            // between the header and the dashed tear line.
            float merchY = triTop - notch - merchPad;
            c.saveState()
             .beginText()
             .setFontAndSize(sansBold, 8f)
             .setFillColor(INK_900)
             .moveText(x + 42f, merchY)
             .showText("YOUR SHOP")
             .endText()
             .restoreState();
            c.saveState()
             .beginText()
             .setFontAndSize(sansBold, 12f)
             .setFillColor(INK_900)
             .moveText(x + 42f, merchY - 14f)
             .showText("1.50")
             .endText()
             .restoreState();
            c.saveState()
             .beginText()
             .setFontAndSize(sans, 7f)
             .setFillColor(INK_500)
             .moveText(x + 42f + 22f, merchY - 14f)
             .showText("USD")
             .endText()
             .restoreState();

            // Dashed tear line. Spec: 15px wide dash + ~4px gap (matches
            // the 65/35 stop in `.dashed-line`).
            float tearY = merchY - 28f;
            c.saveState()
             .setStrokeColor(TEAR_GREY)
             .setLineWidth(1.2f)
             .setLineDash(9.75f, 5.25f)
             .moveTo(x + 6f,     tearY)
             .lineTo(x + W - 6f, tearY)
             .stroke()
             .restoreState();

            // QR placeholder — soft grey square with a subtle ring so devs
            // can clearly see WHERE their QR PNG slots in. NOT a real QR,
            // since the real one comes from /api/generate_qr at runtime.
            float qrX = x + (W - qrSize) / 2f;
            float qrY = y + ((tearY - y) - qrSize) / 2f;
            c.saveState()
             .setFillColor(new DeviceRgb(248, 250, 252))
             .setStrokeColor(new DeviceRgb(220, 224, 232))
             .setLineWidth(0.8f)
             .rectangle(qrX, qrY, qrSize, qrSize)
             .fillStroke()
             .restoreState();

            // Tiny "QR slot" hint inside the placeholder so it's obvious
            // this is where the bound image lands. Centered both axes.
            String hint = "QR";
            float hintSize = 11f;
            float hintWidth = sansBold.getWidth(hint, hintSize);
            c.saveState()
             .beginText()
             .setFontAndSize(sansBold, hintSize)
             .setFillColor(INK_400)
             .moveText(qrX + (qrSize - hintWidth) / 2f,
                       qrY + qrSize / 2f - hintSize / 2f)
             .showText(hint)
             .endText()
             .restoreState();
        }
    }

    /* ── Verbatim snippets the developer copy-pastes from the PDF ── */

    private static final String KHQR_TSX_SNIPPET =
              "// React + Tailwind — fixed 273 × 396, drop straight into your project.\n"
            + "// `qrImage` is the data URL or base64 PNG from /api/generate_qr.\n"
            + "export function KhqrReceiptCard({\n"
            + "  bankName, merchantName, amount, currency = \"USD\", qrImage,\n"
            + "}) {\n"
            + "  return (\n"
            + "    <div className=\"font-sans bg-white text-[rgb(8,27,55)]\n"
            + "                    max-w-[400px] w-full rounded-xl p-6 shadow-lg fade-in\">\n"
            + "      <div className=\"px-6\">\n"
            + "        <div className=\"py-6\">\n"
            + "          <h3 className=\"text-2xl font-normal\">{bankName} KHQR</h3>\n"
            + "        </div>\n"
            + "        <div className=\"flex justify-center\">\n"
            + "          <div className=\"rounded-[22px] qr-card h-[396px] w-[273px]\n"
            + "                          flex flex-col bg-white\">\n"
            + "            <div className=\"mb-8\">\n"
            + "              <div className=\"flex items-center justify-center\n"
            + "                              bg-[rgb(226,26,26)] rounded-t-[21px]\n"
            + "                              h-[42.6562px]\">\n"
            + "                <KhqrLogoSvg />\n"
            + "              </div>\n"
            + "              <div className=\"flex justify-end\">\n"
            + "                <div className=\"red-triangle\"/>\n"
            + "              </div>\n"
            + "              <div className=\"dashed-line py-[15px] px-3 pl-[42px]\">\n"
            + "                <span className=\"text-[10px]\">{merchantName}</span>\n"
            + "                <div className=\"mt-1 flex items-center text-sm font-bold w-full\">\n"
            + "                  <span>{amount.toFixed(2)}</span>\n"
            + "                  <span className=\"text-[8px] font-normal ml-2\">{currency}</span>\n"
            + "                </div>\n"
            + "              </div>\n"
            + "            </div>\n"
            + "            <div className=\"flex justify-center items-center relative flex-1\">\n"
            + "              <div className=\"relative -mt-[5px]\">\n"
            + "                <img src={qrImage} alt=\"QR\"\n"
            + "                     className=\"m-auto h-auto max-w-[195px] w-[195px]\"/>\n"
            + "              </div>\n"
            + "            </div>\n"
            + "          </div>\n"
            + "        </div>\n"
            + "        <div className=\"text-center p-6 w-[273px] mt-2 mx-auto\">\n"
            + "          <p className=\"text-sm text-gray-400\">Scan with any KHQR app</p>\n"
            + "        </div>\n"
            + "      </div>\n"
            + "    </div>\n"
            + "  );\n"
            + "}";

    private static final String KHQR_CSS_SNIPPET =
              "/* globals.css — these four classes are NOT covered by Tailwind. */\n"
            + ".qr-card {\n"
            + "  box-shadow: rgba(0,0,0,0.08) 0 8px 16px 0;\n"
            + "}\n"
            + "\n"
            + ".dashed-line {\n"
            + "  background: linear-gradient(90deg,\n"
            + "    rgba(128,128,128,.85) 65%, rgba(255,255,255,0) 0);\n"
            + "  background-size: 15px 1.5px;\n"
            + "  background-position: 50% 100%;\n"
            + "  background-repeat: repeat-x;\n"
            + "}\n"
            + "\n"
            + ".red-triangle {\n"
            + "  border-left: 19.5px solid transparent;\n"
            + "  border-top:  19.5px solid rgb(226,26,26);\n"
            + "  height: 0;\n"
            + "  width: 0;\n"
            + "}\n"
            + "\n"
            + "@keyframes fadeIn {\n"
            + "  from { opacity: 0; transform: scale(.95); }\n"
            + "  to   { opacity: 1; transform: scale(1); }\n"
            + "}\n"
            + ".fade-in { animation: fadeIn .4s ease-out forwards; }";

    private static final String KHQR_LOGO_SVG_SNIPPET =
              "<svg width=\"60\" height=\"14\" viewBox=\"0 0 60 14\" fill=\"none\"\n"
            + "     xmlns=\"http://www.w3.org/2000/svg\">\n"
            + "  <path d=\"M39.006 5.19439V9.59764H34.5318C34.0729 9.59764 33.7288 9.2307 \n"
            + "           33.7288 8.80731V5.22264C33.7288 4.77103 34.1016 4.43231 \n"
            + "           34.5318 4.43231H38.1743C38.6619 4.40408 39.006 4.74278 \n"
            + "           39.006 5.19439Z\" fill=\"white\"/>\n"
            + "  <path d=\"M59.9717 6.97176H57.7345C57.7345 4.34676 55.5548 2.20159 \n"
            + "           52.8875 2.20159C50.7651 2.20159 48.9008 3.55645 48.2699 \n"
            + "           5.53225C48.1265 6.01209 48.0404 6.49192 48.0404 \n"
            + "           6.97176V13.9718H47.9831C46.7785 13.9718 45.8033 13.0121 \n"
            + "           45.8033 11.8266V6.97176H45.832C45.832 5.05241 46.6351 \n"
            + "           3.21773 48.0691 1.89112C49.3884 0.677406 51.1093 0 52.9162 \n"
            + "           0C56.8168 0 59.9717 3.13305 59.9717 6.97176Z\" fill=\"white\"/>\n"
            + "  <path d=\"M59.9999 13.9718L56.845 14L56.0706 13.2379L54.3497 \n"
            + "           11.5444L51.9692 9.20166H55.1241L59.9999 13.9718Z\" fill=\"white\"/>\n"
            + "  <path d=\"M39.7517 11.7702H33.0117C32.1799 11.7702 31.5203 11.121 \n"
            + "           31.5203 10.3024V3.66936C31.5203 2.85081 32.1799 2.20159 \n"
            + "           33.0117 2.20159H39.7517C40.5834 2.20159 41.2431 2.85081 \n"
            + "           41.2431 3.66936V10.3024L43.4802 12.504V2.14515C43.4802 \n"
            + "           0.959671 42.505 0 41.3005 0H31.4629C30.2583 0 29.2832 \n"
            + "           0.959671 29.2832 2.14515V11.8266C29.2832 13.0121 30.2583 \n"
            + "           13.9718 31.4629 13.9718H41.9888L39.7517 11.7702Z\" fill=\"white\"/>\n"
            + "  <path d=\"M12.3614 14H9.20656L2.60996 7.47984V14H0V0H2.60996V6.2379L8.94843 \n"
            + "           0H12.046L5.16255 6.71772L12.3614 14Z\" fill=\"white\"/>\n"
            + "  <path d=\"M24.1492 0H26.7018V14H24.1492V7.93145H16.8643V14H14.3117V0H16.8643V5.84273H24.1492V0Z\"\n"
            + "        fill=\"white\"/>\n"
            + "</svg>";

    private static final String KHQR_PLAIN_HTML_SNIPPET =
              "<!-- Plain HTML — no React, no Tailwind. Inline styles only. -->\n"
            + "<div style=\"width:273px;height:396px;border-radius:22px;\n"
            + "            background:#fff;box-shadow:0 8px 16px rgba(0,0,0,.08);\n"
            + "            font-family:Arial,Helvetica,sans-serif;\n"
            + "            color:rgb(8,27,55);position:relative;overflow:hidden;\">\n"
            + "  <!-- red header bar -->\n"
            + "  <div style=\"background:rgb(226,26,26);height:42.6562px;\n"
            + "              border-top-left-radius:21px;border-top-right-radius:21px;\n"
            + "              display:flex;align-items:center;justify-content:center;\n"
            + "              color:#fff;font-weight:700;letter-spacing:2px;\">\n"
            + "    KHQR <!-- replace with the verbatim SVG above for pixel-parity -->\n"
            + "  </div>\n"
            + "  <!-- red triangle notch, top-right -->\n"
            + "  <div style=\"width:0;height:0;margin-left:auto;\n"
            + "              border-left:19.5px solid transparent;\n"
            + "              border-top:19.5px solid rgb(226,26,26);\"></div>\n"
            + "  <!-- merchant + amount + dashed tear line -->\n"
            + "  <div style=\"padding:15px 12px 15px 42px;\n"
            + "              background-image:linear-gradient(90deg,\n"
            + "                rgba(128,128,128,.85) 65%,rgba(255,255,255,0) 0);\n"
            + "              background-size:15px 1.5px;background-position:50% 100%;\n"
            + "              background-repeat:repeat-x;\">\n"
            + "    <div style=\"font-size:10px;\">YOUR SHOP</div>\n"
            + "    <div style=\"font-weight:700;font-size:14px;margin-top:4px;\">\n"
            + "      1.50 <span style=\"font-size:8px;font-weight:400;margin-left:8px;\">USD</span>\n"
            + "    </div>\n"
            + "  </div>\n"
            + "  <!-- QR slot — bind src to the qr_image returned by /api/generate_qr -->\n"
            + "  <div style=\"display:flex;align-items:center;justify-content:center;\n"
            + "              flex:1;height:calc(100% - 132px);\">\n"
            + "    <img src=\"{qrImage}\" alt=\"QR\" width=\"195\" height=\"195\"\n"
            + "         style=\"max-width:195px;width:195px;height:auto;\"/>\n"
            + "  </div>\n"
            + "</div>";
}
