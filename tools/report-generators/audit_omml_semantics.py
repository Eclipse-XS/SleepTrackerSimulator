from pathlib import Path
import zipfile

from lxml import etree

DOCX = Path(r"C:\Users\Eclipse\Desktop\7 семестр\Програмування мобільних смарт-систем\ЛР-1_МПСС_Гончаров_Вадим_ОІ-43_final.docx")
XSL = Path(r"C:\Program Files\Microsoft Office\root\Office16\OMML2MML.XSL")
NS = {"m": "http://schemas.openxmlformats.org/officeDocument/2006/math"}

with zipfile.ZipFile(DOCX) as archive:
    root = etree.fromstring(archive.read("word/document.xml"))

transform = etree.XSLT(etree.parse(str(XSL)))
equations = root.xpath("//m:oMath", namespaces=NS)
for index, equation in enumerate(equations, 1):
    result = transform(equation)
    text = "".join(result.getroot().itertext())
    print(f"equation_{index}: {text}")
    print(etree.tostring(result.getroot(), encoding="unicode"))
