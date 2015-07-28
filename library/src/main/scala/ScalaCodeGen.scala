package sbt.datatype
import scala.compat.Platform.EOL
import java.io.File

/**
 * Code generator for Scala.
 */
class ScalaCodeGen(genFile: Definition => File) extends CodeGenerator {

  implicit object indentationConfiguration extends IndentationConfiguration {
    override val indentElement = "  "
    override def augmentIndentAfterTrigger(s: String) =
      s.endsWith("{") ||
      (s.contains(" class ") && s.endsWith("(")) // Constructor definition
    override def reduceIndentTrigger(s: String) = s.startsWith("}")
    override def reduceIndentAfterTrigger(s: String) = s.endsWith(") {") || s.endsWith(")  {") // End of constructor definition
  }


  override def generate(s: Schema): Map[File, String] =
    s.definitions map (generate (_, None, Nil)) reduce (_ merge _) mapValues (_.indented)

  override def generate(e: Enumeration): Map[File, String] = {
    val values =
      e.values map { case (EnumerationValue(name, doc)) =>
        s"""${genDoc(doc)}
           |case object $name extends ${e.name}""".stripMargin
      } mkString EOL

    val code =
      s"""${genPackage(e)}
         |${genDoc(e.doc)}
         |sealed abstract class ${e.name}
         |object ${e.name} {
         |  $values
         |}""".stripMargin

    Map(genFile(e) -> code)
  }

  override def generate(r: Record, parent: Option[Protocol], superFields: List[Field]): Map[File, String] = {
    val allFields = superFields ++ r.fields

    val alternativeCtors =
      genAlternativeConstructors(allFields) mkString EOL

    // If there are no fields, we still need an `apply` method with an empty parameter list.
    val applyOverloads =
      if (allFields.isEmpty) {
        s"def apply(): ${r.name} = new ${r.name}()"
      } else {
        perVersionNumber(allFields) { (provided, byDefault) =>
          val applyParameters =
            provided map genParam mkString ", "

          val ctorCallArguments =
            allFields map {
              case f if provided contains f  => f.name
              case f if byDefault contains f => f.default getOrElse sys.error(s"Need a default value for field ${f.name}.")
            } mkString ", "

          s"def apply($applyParameters): ${r.name} = new ${r.name}($ctorCallArguments)"
        } mkString EOL
      }

    val ctorParameters =
      genCtorParameters(r, allFields) mkString ","

    val superCtorArguments = superFields map (_.name) mkString ", "

    val extendsCode =
      parent map (p => s"extends ${fullyQualifiedName(p)}($superCtorArguments)") getOrElse ""

    val lazyMembers =
      genLazyMembers(r.fields) mkString EOL

    val code =
      s"""${genPackage(r)}
         |${genDoc(r.doc)}
         |final class ${r.name}($ctorParameters) $extendsCode {
         |  $alternativeCtors
         |  $lazyMembers
         |  ${genEquals(r, superFields)}
         |  ${genHashCode(r, superFields)}
         |  ${genToString(r, superFields)}
         |}
         |
         |object ${r.name} {
         |  $applyOverloads
         |}""".stripMargin

    Map(genFile(r) -> code)
  }

  override def generate(p: Protocol, parent: Option[Protocol], superFields: List[Field]): Map[File, String] = {
    val allFields = superFields ++ p.fields

    val alternativeCtors =
      genAlternativeConstructors(allFields) mkString EOL

    val ctorParameters =
      genCtorParameters(p, allFields) mkString ", "

    val superCtorArguments =
      superFields map (_.name) mkString ", "

    val extendsCode =
      parent map (p => s"extends ${fullyQualifiedName(p)}($superCtorArguments)") getOrElse ""

    val lazyMembers =
      genLazyMembers(p.fields) mkString EOL

    val code =
      s"""${genPackage(p)}
         |${genDoc(p.doc)}
         |abstract class ${p.name}($ctorParameters) $extendsCode {
         |  $alternativeCtors
         |  $lazyMembers
         |  ${genEquals(p, superFields)}
         |  ${genHashCode(p, superFields)}
         |  ${genToString(p, superFields)}
         |}""".stripMargin

    Map(genFile(p) -> code) :: (p.children map (generate(_, Some(p), superFields ++ p.fields))) reduce (_ merge _)
  }

  private def genDoc(doc: Option[String]) = doc map (d => s"/** $d */") getOrElse ""

  private def genParam(f: Field): String = s"${f.name}: ${genRealTpe(f.tpe, isParam = true)}"

  private def lookupTpe(tpe: String): String = tpe match {
    case "boolean" => "Boolean"
    case "byte"    => "Byte"
    case "char"    => "Char"
    case "float"   => "Float"
    case "int"     => "Int"
    case "long"    => "Long"
    case "short"   => "Short"
    case "double"  => "Double"
    case other     => other
  }

  private def genRealTpe(tpe: TpeRef, isParam: Boolean) = {
    val scalaTpe = lookupTpe(tpe.name)
    val base = if (tpe.repeated) s"Array[$scalaTpe]" else scalaTpe
    if (tpe.lzy && isParam) s"=> $base" else base
  }

  private def genEquals(cl: ClassLike, superFields: List[Field]) = {
    val allFields = superFields ++ cl.fields
    val comparisonCode =
      if (allFields exists (_.tpe.lzy)) {
        "super.equals(o) // We have lazy members, so use object identity to avoid circularity."
      } else if (allFields.isEmpty) {
        "true"
      } else {
        allFields map (f => s"(this.${f.name} == x.${f.name})") mkString " && "
      }

    s"""override def equals(o: Any): Boolean = o match {
       |  case x: ${cl.name} => $comparisonCode
       |  case _ => false
       |}""".stripMargin
  }

  private def genHashCode(cl: ClassLike, superFields: List[Field]) = {
    val allFields = superFields ++ cl.fields
    val computationCode =
      if (allFields exists (_.tpe.lzy)) {
        s"super.hashCode"
      } else {
        (allFields foldLeft ("17")) { (acc, f) => s"37 * ($acc + ${f.name}.##)" }
      }

    s"""override def hashCode: Int = {
       |  $computationCode
       |}""".stripMargin
  }

  private def genToString(cl: ClassLike, superFields: List[Field]) = {
    val allFields = superFields ++ cl.fields
    val fieldsToString =
      allFields.map(_.name).mkString(" + ", """ + ", " + """, " + ")

    s"""override def toString: String = {
       |  "${cl.name}("$fieldsToString")"
       |}""".stripMargin
  }

  private def genAlternativeConstructors(allFields: List[Field]) =
    perVersionNumber(allFields) {
      case (provided, byDefault) if byDefault.nonEmpty => // Don't duplicate up-to-date constructor
        val ctorParameters =
          provided map genParam mkString ", "
        val thisCallArguments =
          allFields map {
            case f if provided contains f  => f.name
            case f if byDefault contains f => f.default getOrElse sys.error(s"Need a default value for field ${f.name}.")
          } mkString ", "

        s"def this($ctorParameters) = this($thisCallArguments)"

      case _ => ""
    }

  // If a class has a lazy member, it means that the class constructor will have a call-by-name
  // parameter. Because val parameters may not be call-by-name, we prefix the parameter with `_`
  // and we will create the actual lazy val as a regular class member.
  // Non-lazy fields that belong to `cl` are made val parameters.
  private def genCtorParameters(cl: ClassLike, allFields: List[Field]): List[String] =
    allFields map {
      case f if cl.fields.contains(f) && f.tpe.lzy =>
        EOL + "_" + genParam(f)

      case f if cl.fields.contains(f) =>
        s"""$EOL${genDoc(f.doc)}
           |val ${genParam(f)}""".stripMargin

      case f =>
        EOL + genParam(f)
    }

  private def genLazyMembers(fields: List[Field]): List[String] =
    fields filter (_.tpe.lzy) map { f =>
        s"""${genDoc(f.doc)}
           |lazy val ${f.name}: ${genRealTpe(f.tpe, isParam = false)} = _${f.name}""".stripMargin
    }

  private def fullyQualifiedName(d: Definition): String = {
    val path = d.namespace map (ns => ns + ".") getOrElse ""
    path + d.name
  }

  private def genPackage(d: Definition): String =
    d.namespace map (ns => s"package $ns") getOrElse ""

}
