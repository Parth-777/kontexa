"""Generate polished Kontexa product UI mockups for the investor deck."""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ASSETS = Path(__file__).resolve().parent / "assets"
ASSETS.mkdir(parents=True, exist_ok=True)

BG = (11, 18, 32)
SURFACE = (18, 26, 43)
CARD = (21, 31, 51)
TEAL = (20, 184, 166)
BLUE = (59, 130, 246)
WHITE = (248, 250, 252)
MUTED = (148, 163, 184)
SOFT = (203, 213, 225)
LINE = (42, 58, 85)
GREEN = (16, 185, 129)
AMBER = (245, 158, 11)
RED = (244, 63, 94)


def font(size: int, bold: bool = False):
    path = "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf"
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def rr(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def chrome(draw, w, h, title: str):
    rr(draw, (0, 0, w - 1, h - 1), 24, fill=BG, outline=LINE, width=2)
    rr(draw, (0, 0, w - 1, 52), 24, fill=SURFACE)
    draw.rectangle((0, 26, w - 1, 52), fill=SURFACE)
    for i, c in enumerate([(244, 63, 94), (245, 158, 11), (16, 185, 129)]):
        draw.ellipse((18 + i * 18, 18, 30 + i * 18, 30), fill=c)
    draw.text((90, 14), title, font=font(16, True), fill=MUTED)


def sidebar(draw, h):
    rr(draw, (16, 64, 200, h - 16), 16, fill=SURFACE, outline=LINE)
    draw.text((36, 84), "Kontexa", font=font(18, True), fill=WHITE)
    draw.text((36, 110), "Decision Intelligence", font=font(11), fill=MUTED)
    items = [("Home", True), ("Ask", False), ("Investigations", False), ("Catalogue", False), ("Evidence", False)]
    y = 160
    for label, active in items:
        if active:
            rr(draw, (28, y - 6, 188, y + 28), 10, fill=(20, 40, 55))
            draw.text((44, y), label, font=font(13, True), fill=TEAL)
        else:
            draw.text((44, y), label, font=font(13), fill=MUTED)
        y += 42


def save(img: Image.Image, name: str):
    path = ASSETS / name
    img.save(path)
    print("wrote", path)


def mock_home():
    w, h = 1280, 720
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    chrome(d, w, h, "Kontexa  ·  Home")
    sidebar(d, h)
    rr(d, (224, 80, 1248, 200), 18, fill=CARD, outline=LINE)
    d.text((248, 100), "What decision do you need to make?", font=font(28, True), fill=WHITE)
    d.text((248, 145), "Ask in business language. Kontexa investigates your warehouse.", font=font(15), fill=MUTED)
    rr(d, (248, 230, 1248, 310), 16, fill=SURFACE, outline=TEAL)
    d.text((272, 258), "Why did desktop revenue outperform mobile last quarter?", font=font(18), fill=SOFT)
    d.text((1160, 258), "Ask →", font=font(16, True), fill=TEAL)
    cards = [
        ("Revenue", "−4.2%", "vs prior month", RED),
        ("Retention", "91.4%", "stable", GREEN),
        ("Margin", "28.1%", "+0.6 pts", TEAL),
    ]
    for i, (t, v, s, c) in enumerate(cards):
        x = 248 + i * 340
        rr(d, (x, 350, x + 310, 520), 16, fill=CARD, outline=LINE)
        d.text((x + 24, 375), t, font=font(14), fill=MUTED)
        d.text((x + 24, 415), v, font=font(36, True), fill=c)
        d.text((x + 24, 470), s, font=font(13), fill=MUTED)
    d.text((248, 560), "Recent investigations", font=font(14, True), fill=MUTED)
    for i, q in enumerate([
        "Why did churn rise in enterprise accounts?",
        "Which channels drove the margin recovery?",
    ]):
        y = 595 + i * 42
        rr(d, (248, y, 1248, y + 36), 10, fill=SURFACE)
        d.text((272, y + 8), q, font=font(13), fill=SOFT)
    save(img, "mock-home.png")


def mock_ask():
    w, h = 1280, 720
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    chrome(d, w, h, "Kontexa  ·  Ask")
    sidebar(d, h)
    rr(d, (224, 80, 1248, 680), 18, fill=CARD, outline=LINE)
    d.text((256, 110), "Investigation", font=font(12, True), fill=TEAL)
    d.text((256, 140), "Why is desktop traffic higher than mobile?", font=font(26, True), fill=WHITE)
    d.text((256, 190), "Scope: Google Analytics warehouse  ·  Catalogue: approved", font=font(13), fill=MUTED)
    # typing / planning state
    rr(d, (256, 250, 1216, 340), 14, fill=SURFACE, outline=LINE)
    d.text((280, 275), "Planning evidence program…", font=font(16, True), fill=WHITE)
    d.text((280, 305), "Identifying competing hypotheses from the business catalogue", font=font(13), fill=MUTED)
    for i, label in enumerate(["Catalogue bound", "Hypotheses drafted", "Evidence requests queued"]):
        x = 280 + i * 300
        rr(d, (x, 390, x + 270, 470), 12, fill=SURFACE, outline=TEAL if i < 2 else LINE)
        d.text((x + 20, 415), f"{'✓' if i < 2 else '…'}  {label}", font=font(14, True), fill=TEAL if i < 2 else MUTED)
    d.text((280, 520), "Question roles this impacts", font=font(13, True), fill=MUTED)
    for i, role in enumerate(["VP Marketing", "Growth Lead", "Product Manager", "BI Analyst"]):
        x = 280 + i * 230
        rr(d, (x, 555, x + 210, 600), 20, fill=SURFACE, outline=LINE)
        d.text((x + 20, 567), role, font=font(13), fill=SOFT)
    save(img, "mock-ask.png")


def mock_answer():
    w, h = 1280, 720
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    chrome(d, w, h, "Kontexa  ·  Evidence-backed answer")
    sidebar(d, h)
    rr(d, (224, 80, 820, 300), 18, fill=CARD, outline=LINE)
    d.text((248, 100), "Executive answer", font=font(12, True), fill=TEAL)
    d.text((248, 130), "Desktop visits are 5.6× mobile in this period.", font=font(20, True), fill=WHITE)
    d.text((248, 175), "Comparison evidence establishes the gap. Leading hypothesis:", font=font(13), fill=MUTED)
    d.text((248, 205), "Direct traffic dominates desktop sessions.", font=font(15, True), fill=SOFT)
    d.text((248, 245), "Confidence: MODERATE  ·  Catalogue gaps noted", font=font(12), fill=AMBER)
    rr(d, (848, 80, 1248, 300), 18, fill=CARD, outline=LINE)
    d.text((872, 105), "Key numbers", font=font(12, True), fill=MUTED)
    for i, (k, v) in enumerate([("Desktop", "1,412"), ("Mobile", "250"), ("Direct share", "high")]):
        y = 145 + i * 45
        d.text((872, y), k, font=font(14), fill=MUTED)
        d.text((1040, y), v, font=font(16, True), fill=WHITE)
    rr(d, (224, 320, 1248, 680), 18, fill=CARD, outline=LINE)
    d.text((248, 345), "Evidence findings", font=font(14, True), fill=MUTED)
    findings = [
        ("COMPARISON", "Desktop 1,412 visits vs mobile 250 — phenomenon established."),
        ("PRIMARY · H2", "Direct is the leading source on desktop in warehouse rows."),
        ("UNKNOWN", "Marketing spend & campaign attribution unavailable in catalogue."),
    ]
    for i, (tag, text) in enumerate(findings):
        y = 390 + i * 80
        rr(d, (248, y, 1216, y + 65), 12, fill=SURFACE, outline=LINE)
        d.text((272, y + 12), tag, font=font(11, True), fill=TEAL)
        d.text((272, y + 34), text, font=font(14), fill=SOFT)
    save(img, "mock-answer.png")


def mock_charts():
    w, h = 1280, 720
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    chrome(d, w, h, "Kontexa  ·  Charts")
    sidebar(d, h)
    rr(d, (224, 80, 1248, 680), 18, fill=CARD, outline=LINE)
    d.text((256, 110), "Device visits", font=font(22, True), fill=WHITE)
    d.text((256, 150), "Warehouse series rendered from executed evidence", font=font(13), fill=MUTED)
    # bars
    bars = [("desktop", 1412, TEAL), ("mobile", 250, BLUE), ("tablet", 49, (99, 102, 241))]
    max_v = 1412
    base_y = 580
    for i, (label, val, color) in enumerate(bars):
        x = 320 + i * 280
        bh = int(280 * val / max_v)
        rr(d, (x, base_y - bh, x + 160, base_y), 10, fill=color)
        d.text((x, base_y + 16), label, font=font(14), fill=MUTED)
        d.text((x, base_y - bh - 32), str(val), font=font(16, True), fill=WHITE)
    save(img, "mock-charts.png")


def mock_investigation():
    w, h = 1280, 720
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    chrome(d, w, h, "Kontexa  ·  Investigation reasoning")
    sidebar(d, h)
    d.text((224, 80), "Competing hypotheses", font=font(22, True), fill=WHITE)
    hyps = [
        ("H1", "ALTERNATIVE", "Organic traffic favors desktop", False),
        ("H2", "PRIMARY", "Direct traffic dominates desktop", True),
        ("H3", "REJECTED", "Engagement higher on desktop — untestable", False),
    ]
    for i, (hid, status, claim, active) in enumerate(hyps):
        y = 130 + i * 100
        rr(d, (224, y, 1248, y + 85), 14, fill=CARD, outline=TEAL if active else LINE)
        d.text((248, y + 18), f"{hid}  ·  {status}", font=font(12, True), fill=TEAL if active else MUTED)
        d.text((248, y + 45), claim, font=font(16, True), fill=WHITE)
    d.text((224, 460), "Evidence purposes linked to warehouse requests", font=font(13, True), fill=MUTED)
    for i, p in enumerate(["COMPARISON", "PRIMARY", "VALIDATION", "CONTEXT"]):
        x = 224 + i * 260
        rr(d, (x, 500, x + 240, 560), 12, fill=SURFACE, outline=LINE)
        d.text((x + 24, 520), p, font=font(14, True), fill=SOFT)
    save(img, "mock-investigation.png")


def mock_executive():
    w, h = 1280, 720
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    chrome(d, w, h, "Kontexa  ·  Executive insight")
    sidebar(d, h)
    rr(d, (224, 80, 1248, 250), 18, fill=CARD, outline=TEAL)
    d.text((256, 105), "Board-ready insight", font=font(12, True), fill=TEAL)
    d.text((256, 140), "Desktop demand is real — and concentrated in direct traffic.", font=font(24, True), fill=WHITE)
    d.text((256, 190), "Act on acquisition mix for desktop; do not treat engagement as proven.", font=font(14), fill=SOFT)
    # three columns
    cols = [
        ("Evidence", "Gap confirmed in warehouse\nDirect leads desktop mix", GREEN),
        ("Hypothesis status", "H2 supported by rows\nH3 untestable in catalogue", TEAL),
        ("Decision implication", "Reallocate desktop acquisition\nInstrument engagement metrics", BLUE),
    ]
    for i, (t, b, c) in enumerate(cols):
        x = 224 + i * 350
        rr(d, (x, 290, x + 330, 560), 16, fill=CARD, outline=LINE)
        d.rectangle((x, 290, x + 8, 560), fill=c)
        d.text((x + 28, 320), t, font=font(16, True), fill=WHITE)
        d.text((x + 28, 370), b, font=font(14), fill=MUTED)
    d.text((256, 600), "Provenance: 3 evidence requests · warehouse-backed · no invented metrics", font=font(13), fill=MUTED)
    save(img, "mock-executive.png")


if __name__ == "__main__":
    mock_home()
    mock_ask()
    mock_answer()
    mock_charts()
    mock_investigation()
    mock_executive()
    print("mockups complete")
