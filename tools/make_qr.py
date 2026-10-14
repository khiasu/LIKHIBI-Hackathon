"""
Print-ready QR card for the Likhibi contribution page.

    python tools/make_qr.py https://example.trycloudflare.com

Writes likhibi_contribute_qr.png (A6 at 300 dpi) next to this script. The link is
whatever the Cloudflare tunnel printed; the card points to its Contribute page.
"""

import sys
from pathlib import Path

import qrcode
from PIL import Image, ImageDraw, ImageFont

CREAM, INK, RED = (247, 244, 238), (28, 25, 23), (198, 40, 40)


def font(size: int, bold: bool = False):
    for name in (("arialbd.ttf" if bold else "arial.ttf"), "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    url = sys.argv[1].rstrip("/") + "/#/contribute"
    card = Image.new("RGB", (1240, 1748), CREAM)  # A6 portrait at 300 dpi
    draw = ImageDraw.Draw(card)
    logo_path = Path(__file__).resolve().parents[1] / "docs" / "brand" / "likhibi-mark-1024.png"
    if logo_path.exists():
        logo = Image.open(logo_path).convert("RGBA")
        logo = logo.crop(logo.getbbox()).resize((300, 275))
        card.paste(logo, ((1240 - 300) // 2, 90), logo)
    draw.text((620, 430), "Teach Likhibi a sentence", font=font(72, bold=True), fill=INK, anchor="mm")
    draw.text((620, 520), "Add one thing you say every day in Nagamese.", font=font(40), fill=INK, anchor="mm")
    qr = qrcode.QRCode(border=2, box_size=24, error_correction=qrcode.constants.ERROR_CORRECT_M)
    qr.add_data(url)
    qr.make(fit=True)
    code = qr.make_image(fill_color=INK, back_color=CREAM).convert("RGB").resize((760, 760), Image.NEAREST)
    card.paste(code, ((1240 - 760) // 2, 600))
    draw.text((620, 1430), "You choose how it is shared and credited.", font=font(38), fill=INK, anchor="mm")
    draw.text((620, 1490), "Nothing is published without your consent.", font=font(38), fill=INK, anchor="mm")
    draw.text((620, 1600), "Nagamese te likhibi", font=font(52, bold=True), fill=RED, anchor="mm")
    out = Path(__file__).with_name("likhibi_contribute_qr.png")
    card.save(out, dpi=(300, 300))
    print(f"Saved {out} for {url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
