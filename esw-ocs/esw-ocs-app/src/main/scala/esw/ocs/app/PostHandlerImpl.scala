package esw.ocs.app

import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.StandardRoute
import esw.ocs.api.SequencerAdminApi
import esw.ocs.api.models.codecs.SequencerAdminHttpCodecs
import esw.ocs.api.models.request.SequencerAdminPostRequest
import esw.ocs.api.models.request.SequencerAdminPostRequest._
import mscoket.impl.HttpCodecs
import msocket.api.RequestHandler

class PostHandlerImpl(sequencerAdmin: SequencerAdminApi)
    extends RequestHandler[SequencerAdminPostRequest, StandardRoute]
    with SequencerAdminHttpCodecs
    with HttpCodecs {

  override def handle(request: SequencerAdminPostRequest): StandardRoute = request match {
    case GetSequence               => complete(sequencerAdmin.getSequence)
    case IsAvailable               => complete(sequencerAdmin.isAvailable)
    case IsOnline                  => complete(sequencerAdmin.isOnline)
    case Pause                     => complete(sequencerAdmin.pause)
    case Resume                    => complete(sequencerAdmin.resume)
    case Reset                     => complete(sequencerAdmin.reset())
    case AbortSequence             => complete(sequencerAdmin.abortSequence())
    case GoOnline                  => complete(sequencerAdmin.goOnline())
    case GoOffline                 => complete(sequencerAdmin.goOffline())
    case Add(commands)             => complete(sequencerAdmin.add(commands))
    case Prepend(commands)         => complete(sequencerAdmin.prepend(commands))
    case Replace(id, commands)     => complete(sequencerAdmin.replace(id, commands))
    case InsertAfter(id, commands) => complete(sequencerAdmin.insertAfter(id, commands))
    case Delete(id)                => complete(sequencerAdmin.delete(id))
    case AddBreakpoint(id)         => complete(sequencerAdmin.addBreakpoint(id))
    case RemoveBreakpoint(id)      => complete(sequencerAdmin.removeBreakpoint(id))
  }
}
