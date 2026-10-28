from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import parse_xml
from lxml import etree

xsl = etree.XSLT(etree.parse(r"C:\Program Files\Microsoft Office\root\Office16\MML2OMML.XSL"))
mml = etree.fromstring(b'<math xmlns="http://www.w3.org/1998/Math/MathML"><mrow><mi>x</mi><mo>=</mo><msqrt><msup><mi>y</mi><mn>2</mn></msup></msqrt></mrow></math>')
omml = parse_xml(etree.tostring(xsl(mml).getroot()))
doc = Document()
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p._p.append(omml)
doc.save(r"C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator\tools\test_omml.docx")
