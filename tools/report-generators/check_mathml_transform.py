from lxml import etree

xsl = etree.XSLT(etree.parse(r"C:\Program Files\Microsoft Office\root\Office16\MML2OMML.XSL"))
mathml = etree.fromstring(b'<math xmlns="http://www.w3.org/1998/Math/MathML"><mi>x</mi></math>')
result = xsl(mathml)
print(result.getroot().tag)
print(etree.tostring(result.getroot(), encoding="unicode")[:1000])
