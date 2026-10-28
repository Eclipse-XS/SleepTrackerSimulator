from pathlib import Path
import sys
from PIL import Image, ImageDraw

folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r"C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator\tools\qa_pages")
pages = sorted(folder.glob("page-*.png"))
for start in range(0, len(pages), 4):
    batch = pages[start:start + 4]
    thumbs = []
    for path in batch:
        image = Image.open(path).convert("RGB")
        image.thumbnail((620, 880))
        canvas = Image.new("RGB", (660, 940), "white")
        canvas.paste(image, ((660 - image.width) // 2, 35))
        ImageDraw.Draw(canvas).text((20, 10), path.stem, fill="black")
        thumbs.append(canvas)
    sheet = Image.new("RGB", (1320, 1880), "#d0d0d0")
    for i, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((i % 2) * 660, (i // 2) * 940))
    sheet.save(folder / f"sheet-{start // 4 + 1}.png")
