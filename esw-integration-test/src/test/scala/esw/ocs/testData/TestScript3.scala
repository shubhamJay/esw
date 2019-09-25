//package esw.ocs.testData
//
//import csw.params.core.generics.KeyType.StringKey
//import csw.params.core.models.Prefix
//import csw.params.events.{EventName, SystemEvent}
//import esw.dsl.script.{CswServices, Script}
//
//import scala.compat.java8.FutureConverters.CompletionStageOps
//
//class TestScript3(csw: CswServices) extends Script(csw) {
//
//  handleDiagnosticMode {
//    //todo: try to remove match case
//    case (startTime, hint) =>
//      spawn {
//        // do some actions to go to diagnostic mode based on hint
//        val diagnosticModeParam = StringKey.make("mode").set("diagnostic")
//        val event               = SystemEvent(Prefix("tcs.test"), EventName("diagnostic-data")).add(diagnosticModeParam)
//        csw.publishEvent(event).toScala.await
//      }
//  }
//
//  handleOperationsMode {
//    spawn {
//      // do some actions to go to operations mode
//      val operationsModeParam = StringKey.make("mode").set("operations")
//      val event               = SystemEvent(Prefix("tcs.test"), EventName("diagnostic-data")).add(operationsModeParam)
//      csw.publishEvent(event).toScala.await
//    }
//  }
//}
