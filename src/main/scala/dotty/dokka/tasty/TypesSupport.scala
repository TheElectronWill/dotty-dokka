package dotty.dokka.tasty

import org.jetbrains.dokka.model._
import org.jetbrains.dokka.model.{Projection => JProjection}
import collection.JavaConverters._

trait TypesSupport:
    self: TastyParser =>
    import reflect._

    extension on (tpeTree: Tree):
        def dokkaType(using cxt: reflect.Context): Bound =
            val data = tpeTree match
                case TypeBoundsTree(low, high) => typeBound(low.tpe, low = true) ++ typeBound(high.tpe, low = false)
                case tpeTree: TypeTree =>  inner(tpeTree.tpe)
                case term:  Term => inner(term.tpe)

            new TypeConstructor(tpeTree.symbol.dri, data.asJava, FunctionModifiers.NONE)
     
    private def text(str: String): JProjection = new UnresolvedBound(str)
    
    private def texts(str: String): List[JProjection] = List(text(str))

    
    private def link(symbol: reflect.Symbol)(using cxt: reflect.Context): JProjection = new OtherParameter(symbol.dri, symbol.name)
    
    private def commas(lists: List[List[JProjection]]) = lists match
        case List(single) => single
        case other => other.reduce((r, e) => r ++ texts(", ") ++ e)
    
    private def isRepeated(tpeAnnotation: Term) =
        // For some reason annotation.tpe.typeSymbol != defn.RepeatedParamClass
        // annotation.tpe.typeSymbol prints 'class Repeated' and defn.RepeatedParamClass prints 'class <repeated>'
        tpeAnnotation.tpe.typeSymbol.toString == "class Repeated"

    // TODO #23 add support for all types signatures that makes sense
    private def inner(tp: reflect.TypeOrBounds)(using cxt: reflect.Context): List[JProjection] =
        def noSupported(name: String): List[JProjection] =
            println(s"WARN: Unsupported type: $name: ${tp.show}") 
            List(text(s"Unsupported[$name]")) 
            
        tp match
            case OrType(left, right) => inner(left) ++ texts(" | ") ++ inner(right)
            case AndType(left, right) => inner(left) ++ texts(" & ") ++ inner(right)
            case ByNameType(tpe) => text("=> ") :: inner(tpe)
            case ConstantType(constant) => 
                texts(constant.value match
                    case c: Char => s"'$c'"
                    case other => other.toString
                )
            case ThisType(tpe) => inner(tpe)
            case AnnotatedType(AppliedType(_, Seq(tpe)), annotation) if isRepeated(annotation) =>
                inner(tpe) :+ text("*")
            case AnnotatedType(tpe, _) => 
                inner(tpe)
            case tl @ TypeLambda(paramNames, paramTypes, resType) => noSupported(s"TypeLambda: ${paramNames} , ")  //TOFIX
            case r: Refinement => { //(parent, name, info)
                def parseRefinedType(r: Refinement): List[JProjection] = {
                    (r.parent match{
                        case t: TypeRef if t.typeSymbol == defn.ObjectClass => texts("{ ")
                        case t: TypeRef => inner(t) ++ texts(" { ")
                        case r: Refinement => parseRefinedType(r) ++ texts("; ")
                    }) ++ parseRefinedElem(r.name, r.info)
                }
                def parseRefinedElem(name: String, info: TypeOrBounds): List[JProjection] = info match {
                    case m: MethodType => {
                        def getParamList: List[JProjection] = 
                            texts("(")
                            ++ m.paramNames.zip(m.paramTypes).map{ case (name, tp) => texts(s"$name: ") ++ inner(tp)}
                                .reduceLeftOption((acc: List[JProjection], elem: List[JProjection]) => acc ++ texts(", ") ++ elem).getOrElse(List())
                            ++ texts(")")
                        texts(s"def $name") ++ getParamList ++ texts(": ") ++ inner(m.resType)
                    }
                    case ByNameType(tp) => texts(s"def $name: ") ++ inner(tp)
                    case t: TypeBounds => texts(s"type $name") ++ inner(t)
                    case t: TypeRef => texts(s"val $name: ") ++ inner(t)
                    case other => {println(info); noSupported("Not supported type in refinement"); List()}
                }
                parseRefinedType(r) ++ texts(" }")
            }
            case AppliedType(tpe, typeOrBoundsList) =>
                if tpe.isFunctionType then
                    typeOrBoundsList match
                        case Nil => 
                            Nil
                        case Seq(rtpe) => 
                            text("() => ") :: inner(rtpe)
                        case Seq(arg, rtpe) => 
                            inner(arg) ++ texts(" => ") ++ inner(rtpe)
                        case args => 
                            texts("(") ++ commas(args.drop(1).map(inner)) ++ texts(") =>") ++ inner(args.last)
                // else if (tpe.) // TODO support tuples here
                else inner(tpe) ++ texts("[") ++ commas(typeOrBoundsList.map(inner)) ++ texts("]")

            case tp @ TypeRef(qual, typeName) =>
                qual match {
                    case r: RecursiveThis => texts(s"this.$typeName")
                    case _: Type | _: NoPrefix => List(link(tp.typeSymbol))
                    case other => noSupported(s"Type.qual: $other") 
                }    
                // convertTypeOrBoundsToReference(reflect)(qual) match {
                //     case TypeReference(label, link, xs, _) => TypeReference(typeName, link + "/" + label, xs, true)
                //     case EmptyReference => TypeReference(typeName, "", Nil, true)
                //     case _ if tp.typeSymbol.exists =>
                //     tp.typeSymbol match {
                //         // NOTE: Only TypeRefs can reference ClassDefSymbols
                //         case sym if sym.isClassDef => //Need to be split because these types have their own file
                //         convertTypeOrBoundsToReference(reflect)(qual) match {
                //             case TypeReference(label, link, xs, _) => TypeReference(sym.name, link + "/" + label, xs, true)
                //             case EmptyReference if sym.name == "<root>" | sym.name == "_root_" => EmptyReference
                //             case EmptyReference => TypeReference(sym.name, "", Nil, true)
                //             case _ => throw Exception("Match error in SymRef/TypeOrBounds/ClassDef. This should not happen, please open an issue. " + convertTypeOrBoundsToReference(reflect)(qual))
                //         }

                //         // NOTE: This branch handles packages, which are now TypeRefs
                //         case sym if sym.isTerm || sym.isTypeDef =>
                //         convertTypeOrBoundsToReference(reflect)(qual) match {
                //             case TypeReference(label, link, xs, _) => TypeReference(sym.name, link + "/" + label, xs)
                //             case EmptyReference if sym.name == "<root>" | sym.name == "_root_" => EmptyReference
                //             case EmptyReference => TypeReference(sym.name, "", Nil)
                //             case _ => throw Exception("Match error in SymRef/TypeOrBounds/Other. This should not happen, please open an issue. " + convertTypeOrBoundsToReference(reflect)(qual))
                //         }
                //         case sym => throw Exception("Match error in SymRef. This should not happen, please open an issue. " + sym)
                //     }
                //     case _ =>
                //     throw Exception("Match error in TypeRef. This should not happen, please open an issue. " + convertTypeOrBoundsToReference(reflect)(qual))
                // }
            case TermRef(qual, typeName) =>
                // convertTypeOrBoundsToReference(reflect)(qual) match {
                //     case TypeReference(label, link, xs, _) => TypeReference(typeName + "$", link + "/" + label, xs)
                //     case EmptyReference => TypeReference(typeName, "", Nil)
                //     case _ => throw Exception("Match error in TermRef. This should not happen, please open an issue. " + convertTypeOrBoundsToReference(reflect)(qual))
                // }
                noSupported("TypeRef") 

            // NOTE: old SymRefs are now either TypeRefs or TermRefs - the logic here needs to be moved into above branches
            // NOTE: _.symbol on *Ref returns its symbol
            // case SymRef(symbol, typeOrBounds) => symbol match {
            // }
            // case _ => throw Exception("No match for type in conversion to Reference. This should not happen, please open an issue. " + tp)
            case TypeBounds(low, hi) =>
                if(low == hi) texts(" = ") ++ inner(low)
                else typeBound(low, low = true) ++ typeBound(hi, low = false)
            
            case reflect.NoPrefix() => Nil

            case MatchType(bond, sc, cases) =>
                val casesTexts = cases.flatMap {
                    case MatchTypeCase(from, to) =>
                        texts("  case ") ++ inner(from) ++ texts(" => ") ++ inner(to) ++ texts("\n")
                }
                inner(sc) ++ texts(" match {\n") ++ casesTexts ++ texts("}")
            
            case TypeIdent(t) => texts(t)

            case ParamRef(TypeLambda(names, _, _), i) => texts(names.apply(i))

            case RecursiveType(tp) => inner(tp)

    private def typeBound(t: Type, low: Boolean) = 
        val ignore = if(low) t.typeSymbol == defn.NothingClass  else t.typeSymbol == defn.AnyClass
        if ignore then Nil 
        else text(if low then " >: " else " <: ") :: inner(t)
    