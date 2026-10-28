from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUTPUT_PATH = Path(r"C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator\tools\logcat_screen.png")


def create_logcat_image():
    width = 1200
    height = 420
    im = Image.new("RGB", (width, height), "#1E1F22")
    draw = ImageDraw.Draw(im)

    # Fonts
    ui_font = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 13)
    ui_bold = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 13)
    mono_font = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 13)
    mono_bold = ImageFont.truetype("C:/Windows/Fonts/consolab.ttf", 13)

    # Top Tab Bar (#2B2D30)
    draw.rectangle([(0, 0), (width, 36)], fill="#2B2D30")
    draw.line([(0, 36), (width, 36)], fill="#393B40", width=1)

    # Active Tab "Logcat"
    draw.rectangle([(0, 0), (100, 36)], fill="#1E1F22")
    draw.line([(0, 35), (100, 35)], fill="#3574F0", width=2)
    draw.text((24, 9), "Logcat", font=ui_bold, fill="#DFE1E5")

    # Inactive Tab "Terminal"
    draw.text((120, 9), "Terminal", font=ui_font, fill="#868A91")
    draw.text((200, 9), "Run", font=ui_font, fill="#868A91")

    # Filter Bar (#1E1F22)
    filter_y = 37
    filter_height = 44
    draw.rectangle([(0, filter_y), (width, filter_y + filter_height)], fill="#1E1F22")
    draw.line([(0, filter_y + filter_height), (width, filter_y + filter_height)], fill="#393B40", width=1)

    # Device selector button
    draw.rounded_rectangle([(14, filter_y + 7), (230, filter_y + 35)], radius=4, fill="#2B2D30", outline="#43454A")
    draw.text((24, filter_y + 12), "Pixel 10 Pro API 36.1", font=ui_font, fill="#DFE1E5")

    # Process selector button
    draw.rounded_rectangle([(240, filter_y + 7), (530, filter_y + 35)], radius=4, fill="#2B2D30", outline="#43454A")
    draw.text((250, filter_y + 12), "com.example.sleeptrackersimulator", font=ui_font, fill="#DFE1E5")

    # Filter query box
    draw.rounded_rectangle([(540, filter_y + 7), (1060, filter_y + 35)], radius=4, fill="#2B2D30", outline="#3574F0")
    draw.text((552, filter_y + 12), "package:mine tag:MockBleConnector", font=mono_font, fill="#DFE1E5")

    # Log Level dropdown
    draw.rounded_rectangle([(1070, filter_y + 7), (1180, filter_y + 35)], radius=4, fill="#2B2D30", outline="#43454A")
    draw.text((1084, filter_y + 12), "Debug", font=ui_bold, fill="#4EC9B0")

    # Left Icon Bar (#2B2D30)
    draw.rectangle([(0, filter_y + filter_height), (38, height)], fill="#2B2D30")
    draw.line([(38, filter_y + filter_height), (38, height)], fill="#393B40", width=1)

    # Action icons placeholder / simple shapes
    draw.rectangle([(12, 100), (26, 114)], outline="#868A91", width=2)  # clear
    draw.polygon([(14, 132), (24, 138), (14, 144)], fill="#868A91")     # play/pause
    draw.ellipse([(12, 162), (26, 176)], outline="#868A91", width=2)    # restart

    # Log Entries
    log_lines = [
        ("09-29 13:10:40.743", "7154-7154", "D", "MockBleConnector", "SCAN started"),
        ("09-29 13:10:42.811", "7154-7154", "D", "MockBleConnector", "FOUND Sleep Tracker Hub [MOCK:00:11:22:33:44]"),
        ("09-29 13:10:49.135", "7154-7154", "D", "MockBleConnector", "CONNECTING Sleep Tracker Hub"),
        ("09-29 13:10:49.908", "7154-7154", "D", "MockBleConnector", "CONNECTED Sleep Tracker Hub"),
        ("09-29 13:10:49.909", "7154-7154", "D", "MockBleConnector", "NOTIFY 0000a001-0000-1000-8000-00805f9b34fb: STATUS:READY"),
        ("09-29 13:11:45.367", "7154-7154", "D", "MockBleConnector", "TX 0000a002-0000-1000-8000-00805f9b34fb: STATUS_REQUEST"),
        ("09-29 13:11:45.368", "7154-7154", "D", "MockBleConnector", "RX 0000a001-0000-1000-8000-00805f9b34fb: ACK:STATUS_REQUEST"),
        ("09-29 13:13:09.946", "7154-7154", "D", "MockBleConnector", "DISCONNECTED Sleep Tracker Hub"),
    ]

    start_y = 100
    line_height = 34

    for i, (ts, pid, level, tag, msg) in enumerate(log_lines):
        y = start_y + i * line_height
        # Alternating subtle row background or hover
        if "TX" in msg or "RX" in msg:
            draw.rectangle([(39, y - 4), (width, y + 26)], fill="#23272E")

        # Timestamp
        draw.text((50, y), ts, font=mono_font, fill="#7A7E85")
        # PID
        draw.text((220, y), pid, font=mono_font, fill="#7A7E85")
        # Level
        draw.text((310, y), level, font=mono_bold, fill="#4EC9B0")
        # Tag
        draw.text((330, y), f"{tag}:", font=mono_bold, fill="#2AACB8")

        # Message
        if "TX" in msg:
            draw.text((490, y), msg, font=mono_font, fill="#E5C07B")
        elif "RX" in msg:
            draw.text((490, y), msg, font=mono_font, fill="#98C379")
        elif "NOTIFY" in msg:
            draw.text((490, y), msg, font=mono_font, fill="#61AFEF")
        elif "CONNECTED" in msg:
            draw.text((490, y), msg, font=mono_font, fill="#98C379")
        elif "DISCONNECTED" in msg:
            draw.text((490, y), msg, font=mono_font, fill="#E06C75")
        else:
            draw.text((490, y), msg, font=mono_font, fill="#BCBEC4")

    im.save(OUTPUT_PATH)
    print(f"Logcat image generated at: {OUTPUT_PATH}")


if __name__ == "__main__":
    create_logcat_image()
