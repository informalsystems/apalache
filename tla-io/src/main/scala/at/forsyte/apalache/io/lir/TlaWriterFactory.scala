package at.forsyte.apalache.io.lir

import at.forsyte.apalache.io.OutputManager
import at.forsyte.apalache.tla.lir.TlaModule
import OutputManager.Names._

import java.io.{File, FileWriter, PrintWriter}

/**
 * An interface for constructing instances of TlaWriter.
 *
 * @author Igor Konnov
 */
trait TlaWriterFactory {

  def createTlaWriter(printWriter: PrintWriter): TlaWriter

  def createJsonWriter(printWriter: PrintWriter): TlaWriter

  /**
   * Write a module to a file (without appending), in all supported formats (TLA+ and JSON).
   *
   * @param module       TLA module to write
   */
  def writeModuleAllFormats(module: TlaModule, extendedModuleNames: List[String]): Unit = {
    writeModuleToTla(module, extendedModuleNames, None)
    writeModuleToJson(module, extendedModuleNames, None)
  }

  // Internal call, parameterized by output format writer
  //
  // if `writer` supplied, write the module to the given writer, otherwise
  // a default writer is constructed based on the file name, in the intermediate
  // output directory
  protected def writeModuleWithFormatWriter(
      extension: String, createWriter: PrintWriter => TlaWriter, writer: Option[PrintWriter]
  )(
      module: TlaModule, extendedModuleNames: List[String]
  ): Unit = {
    val writef: PrintWriter => Unit = w =>
      try {
        createWriter(w).write(module, extendedModuleNames)
      } finally {
        w.close()
      }

    writer match {
      case Some(w) => writef(w)
      case None    => OutputManager.withWriterRelativeToRunDir(module.name + extension)(writef)
    }
  }

  /**
   * Write a module to a file (without appending), in the TLA+ format.
   *
   * @param module TLA module to write
   * @param writer The writer into which the module should be written (defaults
   *        to a file in the intermdiate output directory, with the name derived
   *        from the module name)
   */
  def writeModuleToTla(
      module: TlaModule, extendedModuleNames: List[String], writer: Option[PrintWriter]
  ): Unit =
    writeModuleWithFormatWriter(".tla", createTlaWriter, writer)(module, extendedModuleNames)

  /**
   * Write a module to a file (without appending), in the Apalache JSON format.
   *
   * @param module TLA module to write
   * @param writer The writer into which the module should be written (defaults
   *        to a file in the intermdiate output directory, with the name derived
   *        from the module name)
   */
  def writeModuleToJson(
      module: TlaModule, extendedModuleNames: List[String], writer: Option[PrintWriter]
  ): Unit =
    writeModuleWithFormatWriter(".json", createJsonWriter, writer)(module, extendedModuleNames)
}
