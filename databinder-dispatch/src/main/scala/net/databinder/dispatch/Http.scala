package net.databinder.dispatch

import java.io.{InputStream,OutputStream,BufferedInputStream,BufferedOutputStream}

import org.apache.http._
import org.apache.http.client._
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.client.methods._
import org.apache.http.client.entity.UrlEncodedFormEntity

import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicNameValuePair
import org.apache.http.protocol.{HTTP, HttpContext}
import org.apache.http.params.{HttpProtocolParams, BasicHttpParams}
import org.apache.http.util.EntityUtils
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}

case class StatusCode(code: Int) extends Exception("Exceptional resoponse code: " + code)

trait Http {
  val client: HttpClient
  
  /** Execute in HttpClient. */
  protected def execute(req: HttpUriRequest) = client.execute(req)
  
  /** Get wrapper */
  def g [T](uri: String) = x[T](new HttpGet(uri))
  
  /** eXecute wrapper */
  def x [T](req: HttpUriRequest) = new {
    /** handle response codes, response, and entity in thunk */
    def apply(thunk: (Int, HttpResponse, Option[HttpEntity]) => T) = {
      val res = execute(req)
      val ent = res.getEntity match {
        case null => None
        case ent => Some(ent)
      }
      try { thunk(res.getStatusLine.getStatusCode, res, ent) }
      finally { ent foreach (_.consumeContent) }
    }
    
    /** Handle reponse entity in thunk if reponse code returns true from chk. */
    def when(chk: Int => Boolean)(thunk: (HttpResponse, Option[HttpEntity]) => T) = this { (code, res, ent) => 
      if (chk(code)) thunk(res, ent)
      else throw StatusCode(code)
    }
    
    /** Handle reponse entity in thunk when response code is 200 - 204 */
    def ok = (this when {code => (200 to 204) contains code}) _
  }
  
  /** Return wrapper for basic request workflow. */
  def apply(uri: String) = new Request(uri)
  
  /** Wrapper to handle common requests, preconfigured as response wrapper for a 
    * get request but defs return other method responders. */
  class Request(uri: String) extends Respond(new HttpGet(uri)) {
    /** Put the given object.toString and return response wrapper. */
    def <<< (body: Any) = {
      val m = new HttpPut(uri)
      m setEntity new StringEntity(body.toString, HTTP.UTF_8)
      HttpProtocolParams.setUseExpectContinue(m.getParams, false)
      new Respond(m)
    }
    /** Post the given key value sequence and return response wrapper. */
    def << (values: (String, Any)*) = {
      val m = new HttpPost(uri)
      m setEntity new UrlEncodedFormEntity(
        java.util.Arrays.asList(
          (values map { case (k, v) => new BasicNameValuePair(k, v.toString) }: _*)
        ),
        HTTP.UTF_8
      )
      new Respond(m)
    }
    /** Post the given map and return response wrapper. */
    def << (values: Map[String, Any]): Respond = <<(values.toArray: _*)
  }
  /** Wrapper for common response handling. */
  class Respond(req: HttpUriRequest) {
    def apply [T] (thunk: (Int, HttpResponse, Option[HttpEntity]) => T) = x (req) (thunk)
    /** Handle response and entity in thunk if OK. */
    def ok [T] (thunk: (HttpResponse, Option[HttpEntity]) => T) = x (req) ok (thunk)
    /** Handle response entity in thunk if OK. */
    def okee [T] (thunk: HttpEntity => T): T = ok { 
      case (_, Some(ent)) => thunk(ent)
      case (res, _) => error("response has no entity: " + res)
    }
    /** Handle InputStream in thunk if OK. */
    def >> [T] (thunk: InputStream => T) = okee (ent => thunk(ent.getContent))
    /** Return response in String if OK. (Don't blow your heap, kids.) */
    def as_str = okee { EntityUtils.toString(_, HTTP.UTF_8) }
    /** Write to the given OutputStream. */
    def >>> (out: OutputStream): Unit = okee { _.writeTo(out) }
    /** Process response as XML document in thunk */
    def <> [T] (thunk: (scala.xml.NodeSeq => T)) = { 
      // an InputStream source is the right way, but ConstructingParser
      // won't let us peek and we're tired of trying
      val full_in = as_str
      val in = full_in.substring(full_in.indexOf('<')) // strip any garbage
      val src = scala.io.Source.fromString(in)
      thunk(scala.xml.parsing.XhtmlParser(src))
    }
    /** Ignore response body if OK. */
    def >| = ok ((r,e) => ())
  }
}

/** DefaultHttpClient with parameters that may be more widely compatible. */
class ConfiguredHttpClient extends DefaultHttpClient {
  override def createHttpParams = {
    val params = new BasicHttpParams
    HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1)
    HttpProtocolParams.setContentCharset(params, HTTP.UTF_8)
    HttpProtocolParams.setUseExpectContinue(params, false)
    params
  }
}

/** client value initialized to a CovfiguredHttpClient instance. */
trait ConfiguredHttp extends Http {
  lazy val client = new ConfiguredHttpClient
}

/** For interaction with a single HTTP host. */
class HttpServer(host: HttpHost) extends ConfiguredHttp {
  def this(hostname: String, port: Int) = this(new HttpHost(hostname, port))
  def this(hostname: String) = this(new HttpHost(hostname))
  /** Uses bound host server in HTTPClient execute. */
  override def execute(req: HttpUriRequest):HttpResponse = {
    preflight(req)
    client.execute(host, req)
  }
  /** Block to be run before every outgoing request */
  private var preflight = { req: HttpUriRequest => () }
  /** @param action run before every outgoing request */
  protected def preflight(action: HttpUriRequest => Unit) {
    preflight = action
  }
  /** Sets authentication credentials for bound host. */
  protected def auth(name: String, password: String) {
    client.getCredentialsProvider.setCredentials(
      new AuthScope(host.getHostName, host.getPort), 
      new UsernamePasswordCredentials(name, password)
    )
  }
}

import org.apache.http.conn.scheme.{Scheme,SchemeRegistry,PlainSocketFactory}
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager

/** May be used directly from any thread, or to return configured single-thread instances. */
object Http extends Http {
  lazy val client = new ConfiguredHttpClient {
    override def createClientConnectionManager() = {
      val registry = new SchemeRegistry()
      registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80))
      registry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443))
      new ThreadSafeClientConnManager(getParams(), registry)
    }
  }
}
