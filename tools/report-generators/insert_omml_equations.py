from pathlib import Path

from docx import Document
from docx.oxml import OxmlElement, parse_xml
from docx.oxml.ns import qn
from lxml import etree


PATH = Path(r"C:\Users\Eclipse\Desktop\7 семестр\Програмування мобільних смарт-систем\ЛР-1_МПСС_Гончаров_Вадим_ОІ-43_final.docx")
MML2OMML = Path(r"C:\Program Files\Microsoft Office\root\Office16\MML2OMML.XSL")


def el(tag, text=None):
    node = OxmlElement(tag)
    if text is not None:
        node.text = text
    return node


def math_run(text):
    run = el("m:r")
    run.append(el("m:t", text))
    return run


def math_expr(*children):
    expr = el("m:e")
    for child in children:
        expr.append(child)
    return expr


def superscript(base_children, exponent="2"):
    node = el("m:sSup")
    node.append(math_expr(*base_children))
    sup = el("m:sup")
    sup.append(math_run(exponent))
    node.append(sup)
    return node


def subscript(base, sub):
    node = el("m:sSub")
    node.append(math_expr(math_run(base)))
    sub_node = el("m:sub")
    sub_node.append(math_run(sub))
    node.append(sub_node)
    return node


def radical(children):
    node = el("m:rad")
    props = el("m:radPr")
    hide = el("m:degHide")
    hide.set(qn("m:val"), "1")
    props.append(hide)
    node.append(props)
    node.append(el("m:deg"))
    node.append(math_expr(*children))
    return node


def equation_para(children):
    para = el("m:oMathPara")
    props = el("m:oMathParaPr")
    just = el("m:jc")
    just.set(qn("m:val"), "center")
    props.append(just)
    para.append(props)
    math = el("m:oMath")
    for child in children:
        math.append(child)
    para.append(math)
    return para


def first_equation():
    return mathml_equation(r'''<math xmlns="http://www.w3.org/1998/Math/MathML">
      <mrow><mo>|</mo><mi>a</mi><mo>|</mo><mo>=</mo><msqrt><mrow>
      <msup><mi>x</mi><mn>2</mn></msup><mo>+</mo>
      <msup><mi>y</mi><mn>2</mn></msup><mo>+</mo>
      <msup><mi>z</mi><mn>2</mn></msup>
      </mrow></msqrt></mrow></math>''')


def squared_difference(axis):
    return superscript([
        math_run("("),
        subscript(axis, "t"),
        math_run(" − "),
        subscript(axis, "t−1"),
        math_run(")"),
    ])


def second_equation():
    return mathml_equation(r'''<math xmlns="http://www.w3.org/1998/Math/MathML">
      <mrow><mi>Δa</mi><mo>=</mo><msqrt><mrow>
      <msup><mrow><mtext>(</mtext><msub><mi>x</mi><mi>t</mi></msub><mtext>−</mtext><msub><mi>x</mi><mrow><mi>t</mi><mtext>−</mtext><mn>1</mn></mrow></msub><mtext>)</mtext></mrow><mn>2</mn></msup><mo>+</mo>
      <msup><mrow><mtext>(</mtext><msub><mi>y</mi><mi>t</mi></msub><mtext>−</mtext><msub><mi>y</mi><mrow><mi>t</mi><mtext>−</mtext><mn>1</mn></mrow></msub><mtext>)</mtext></mrow><mn>2</mn></msup><mo>+</mo>
      <msup><mrow><mtext>(</mtext><msub><mi>z</mi><mi>t</mi></msub><mtext>−</mtext><msub><mi>z</mi><mrow><mi>t</mi><mtext>−</mtext><mn>1</mn></mrow></msub><mtext>)</mtext></mrow><mn>2</mn></msup>
      </mrow></msqrt></mrow></math>''')


def mathml_equation(mathml):
    transform = etree.XSLT(etree.parse(str(MML2OMML)))
    result = transform(etree.fromstring(mathml.encode("utf-8")))
    return parse_xml(etree.tostring(result.getroot()))


def replace_marker(paragraph, equation):
    p = paragraph._p
    for child in list(p):
        if child.tag != qn("w:pPr"):
            p.remove(child)
    p.append(equation)
    paragraph.paragraph_format.keep_together = True
    paragraph.paragraph_format.space_before = 0
    paragraph.paragraph_format.space_after = 0


def main():
    doc = Document(PATH)
    found = set()
    for paragraph in doc.paragraphs:
        text = paragraph.text.strip()
        if text == "[[WORD_EQUATION_1]]":
            replace_marker(paragraph, first_equation())
            found.add(1)
        elif text == "[[WORD_EQUATION_2]]":
            replace_marker(paragraph, second_equation())
            found.add(2)
    if found != {1, 2}:
        raise RuntimeError(f"Equation markers found: {sorted(found)}")
    doc.save(PATH)


if __name__ == "__main__":
    main()
