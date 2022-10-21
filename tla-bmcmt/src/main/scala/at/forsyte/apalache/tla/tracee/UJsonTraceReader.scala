package at.forsyte.apalache.tla.tracee

import at.forsyte.apalache.infra.passes.options.SourceOption
import at.forsyte.apalache.infra.passes.options.SourceOption.FileSource
import at.forsyte.apalache.io.json.impl.{UJsonRep, UJsonScalaFactory, UJsonToTlaViaBuilder}
import at.forsyte.apalache.infra.passes.options.SourceOption._
import at.forsyte.apalache.io.json.JsonDeserializationError
import at.forsyte.apalache.io.lir.TypeTagReader
import at.forsyte.apalache.tla.imp.src.SourceStore
import at.forsyte.apalache.tla.lir.oper.{TlaBoolOper, TlaOper}
import at.forsyte.apalache.tla.lir.{NameEx, OperEx, TlaEx, TlaOperDecl}

import scala.annotation.unused
import scala.util.{Failure, Success, Try}

/**
 * Reads an ITF-format or a TlaJSON-format trace into a TLAIR trace, with an intermediate UJson representation.
 *
 * @author
 *   Jure Kukovec
 */
class UJsonTraceReader(sourceStoreOpt: Option[SourceStore], tagReader: TypeTagReader) extends TraceReader[UJsonRep] {
  private val builder = new UJsonToTlaViaBuilder(sourceStoreOpt)(tagReader)
  override def read(source: SourceOption): UJsonRep = {
    val readable: ujson.Readable = source match {
      case FileSource(f, Format.Json | Format.Itf) => f
      case _ => throw new IllegalArgumentException("File provided with --trace is not a JSON.")
    }

    // Rethrow as JsonDeserializationError if unable to read
    Try(ujson.read(readable)) match {
      case Success(ujsonVal) => UJsonRep(ujsonVal)
      case Failure(exception) =>
        throw new JsonDeserializationError("Unable to read --trace as JSON.", exception)
    }
  }

  // Assume here that the json is in ITF format
  override def convert(json: UJsonRep): Trace = caseSplitOnJsonFormat(convertITF, convertGenericJSON)(json)

  override def getTraceLength(json: UJsonRep): Int = caseSplitOnJsonFormat(getLengthITF, getLengthGeneric)(json)

  // Performs one of the procedures, depending on whether the json is an itf.json or a generic json
  private def caseSplitOnJsonFormat[T](ifITF: UJsonRep => T, ifGenericJSON: UJsonRep => T)(json: UJsonRep): T = {
    val nameOpt = json.getFieldOpt("name").map(UJsonScalaFactory.asStr)
    if (nameOpt.contains("ApalacheIR")) {
      ifGenericJSON(json)
    } else {
      val formatOpt = for {
        meta <- json.getFieldOpt("#meta")
        format <- meta.getFieldOpt("format")
      } yield UJsonScalaFactory.asStr(format)
      if (formatOpt.contains("ITF")) {
        ifITF(json)
      } else {
        throw new JsonDeserializationError("JSON structure unsupported. Must be ITF or TlaJSON.")
      }
    }
  }

  private def getLengthITF(json: UJsonRep): Int =
    json.getFieldOpt("states").map(seqJSON => UJsonScalaFactory.asSeq(seqJSON).length).getOrElse {
      throw new JsonDeserializationError(s"Provided JSON does not comply with the ITF format.")
    }

  // TODO
  private def convertITF(@unused json: UJsonRep): Trace = IndexedSeq.empty

  private def kvFromAsgn(ex: TlaEx): (String, TlaEx) = ex match {
    case OperEx(TlaOper.eq, NameEx(name), rhs) => name -> rhs
    case _ => throw new JsonDeserializationError(s"Cannot read variable assignment from $ex.")
  }

  private def convertGenericJSON(json: UJsonRep): Trace = {
    val operDecls = for {
      modules <- json.getFieldOpt("modules")
      decls <- UJsonScalaFactory.asSeq(modules).head.getFieldOpt("declarations")
    } yield {
      // drop CInit (head) and Inv (last)
      UJsonScalaFactory.asSeq(decls).tail.dropRight(1).toIndexedSeq.map { decl =>
        builder.asTlaDecl(decl).asInstanceOf[TlaOperDecl]
      }
    }
    // From an indexed sequence of declarations, create a sequence of State-maps.
    operDecls
      .map { decls =>
        decls.map { decl =>
          decl.body match {
            // Edge case: single-var specs
            case OperEx(TlaOper.eq, NameEx(name), rhs) => Map(name -> rhs)
            // general conjunctive case
            case OperEx(TlaBoolOper.and, args @ _*) =>
              Map(args.map(kvFromAsgn): _*)
            // malformed JSON case
            case body =>
              throw new JsonDeserializationError(s"Cannot read state from $body.")
          }
        }
      }
      .getOrElse {
        throw new JsonDeserializationError(s"Trace JSON is incorrectly formatted.")
      }
  }

  private def getLengthGeneric(json: UJsonRep): Int = {
    val lenOpt = for {
      modules <- json.getFieldOpt("modules")
      decls <- UJsonScalaFactory.asSeq(modules).head.getFieldOpt("declarations")
    } yield UJsonScalaFactory.asSeq(decls).length - 2 // we need len-2 for CInit (head) and Inv (last)

    lenOpt.getOrElse {
      throw new JsonDeserializationError(s"Provided JSON does not comply with the Apalache JSON format.")
    }
  }
}
