package moonbox.common.util

import java.io.{ByteArrayInputStream, ObjectInputStream, ObjectOutputStream, _}
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.util.{Collections, Date, Properties, Map => JMap}

import com.typesafe.config.{Config, ConfigFactory}
import moonbox.common.MbLogging

import scala.collection.JavaConverters._

object Utils extends MbLogging {

	def classForName(className: String): Class[_] = {
		Class.forName(className)
	}

	def getEnv = System.getenv()

	def getEnv(key: String): String = getEnv(key)

	def setEnv(key: String, value: String): Unit = {
		setEnv(Map(key -> value))
	}

	def setEnv(newEnv: Map[String, String]): Unit = {
		setEnv(newEnv.asJava)
	}

	def setEnv(newEnv: JMap[String, String]): Unit = {
		try {
			val processEnvironmentClass = classForName("java.lang.ProcessEnvironment")
			val theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment")
			theEnvironmentField.setAccessible(true)
			val env = theEnvironmentField.get(null).asInstanceOf[JMap[String, String]]
			env.putAll(newEnv)

			val theCaseInsensitiveEnvironmentField = processEnvironmentClass.getDeclaredField("theCaseInsensitiveEnvironment")
			theCaseInsensitiveEnvironmentField.setAccessible(true)
			val ciEnv = theCaseInsensitiveEnvironmentField.get(null).asInstanceOf[JMap[String, String]]
			ciEnv.putAll(newEnv)
		} catch {
			case e: NoSuchFieldException =>
				val classes: Array[java.lang.Class[_]] = classOf[Collections].getDeclaredClasses
				val env: JMap[String, String] = System.getenv()
				for (cl <- classes; if "java.util.Collections$UnmodifiableMap".equals(cl.getName)) {
					val field: Field = cl.getDeclaredField("m")
					field.setAccessible(true)
					val map: JMap[String, String] = field.get(env).asInstanceOf[JMap[String, String]]
					map.clear()
					map.putAll(newEnv)
				}
		}

	}

	def serialize[T](o: T): Array[Byte] = {
		val bos = new ByteArrayOutputStream()
		val oos = new ObjectOutputStream(bos)
		oos.writeObject(o)
		oos.close()
		bos.toByteArray
	}

	def deserialize[T](bytes: Array[Byte]): T = {
		val bis = new ByteArrayInputStream(bytes)
		val ois = new ObjectInputStream(bis)
		ois.readObject.asInstanceOf[T]
	}

	def getFormattedClassName(obj: AnyRef): String = {
		obj.getClass.getSimpleName.replace("$", "")
	}

	def getSystemProperties: Map[String, String] = {
		System.getProperties.stringPropertyNames().asScala
			.map(key => (key, System.getProperty(key))).toMap
	}

	def getDefaultLogConfig(env: Map[String, String] = sys.env): Option[String] = {
		val configDir: Option[String] = env.get("MOONBOX_CONF_DIR").orElse(env.get("MOONBOX_HOME")
			.map {t => s"$t${File.separator}conf"})
		if (configDir.isEmpty) {
			System.err.println("conf directory doesn't exist.")
		}
		val configFile = configDir.map (t => new File(s"$t${File.separator}log4j.properties"))
			.filter(_.isFile).map(_.getAbsolutePath)
		if(configFile.isEmpty) {
			System.err.println("log4j.properties doesn't exist.")
		}
		configFile
	}

	def getDefaultPropertiesFile(env: Map[String, String] = sys.env): Option[String] = {

		val configDir: Option[String] = env.get("MOONBOX_CONF_DIR").orElse(env.get("MOONBOX_HOME")
			.map {t => s"$t${File.separator}conf"})
		if (configDir.isEmpty) {
			logWarning("conf directory doesn't exist.")
		}
		val configFile = configDir.map (t => new File(s"$t${File.separator}moonbox-defaults.conf"))
			.filter(_.isFile).map(_.getAbsolutePath)
		if(configFile.isEmpty) {
			logWarning("moonbox-defaults.conf doesn't exist.")
		}
		configFile
	}

	def typesafeConfig2Map(config: Config): Map[String, String] = {
		val map: Map[String, String] = config.entrySet().asScala.map({ entry =>
			entry.getKey -> entry.getValue.unwrapped().toString
		})(collection.breakOut)
		map
	}

	def getConfigFromFile(filename: String): Config = {
		val file = new File(filename)
		require(file.exists, s"Properties file $file does not exist")
		require(file.isFile, s"Properties file $file is not a normal file")
		val config: Config = ConfigFactory.parseFile(file)
		ConfigFactory.load(config)
	}

	def getPropertiesFromFile(filename: String): Map[String, String] = {
		val file = new File(filename)
		require(file.exists, s"Properties file $file does not exist")
		require(file.isFile, s"Properties file $file is not a normal file")

		val inReader: InputStreamReader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
		try {
			val properties = new Properties()
			properties.load(inReader)
			properties.stringPropertyNames().asScala.map(
				k => (k, properties.getProperty(k).trim)
			).toMap
		} catch {
			case e: IOException =>
				throw new Exception(s"Failed when loading moonbox properties from $filename", e)
		} finally {
			inReader.close()
		}
	}

	def getRuntimeJars(env: Map[String, String] = sys.env): List[String] = {
		val plginDir: Option[String] = env.get("MOONBOX_CONF_DIR").orElse(env.get("MOONBOX_HOME").map {t => s"$t${File.separator}runtime"})
		if(plginDir.isEmpty) {
			//TODO
			throw new Exception("$MOONBOX_HOME does not exist")
		} else {
			val lib = new File(plginDir.get)
			if (lib.exists()) {
				val confFile = lib.listFiles().filter {_.isFile}.map (_.getAbsolutePath)
				confFile.toList
			} else {
				List()
			}
		}
	}

	def delete (file: File) {
		if (file == null) {
			return
		} else if (file.isDirectory) {
			val files: Array[File] = file.listFiles
			if (files != null) {
				for (f <- files) delete(f)
			}
			file.delete
		} else {
			file.delete
		}
	}

	def checkHost(host: String, message: String): Unit = {
		assert(host.indexOf(':') == -1, message)
	}

	def now: Long = System.currentTimeMillis()

	def allEquals[T](data: Seq[T]): Boolean = data match {
		case Nil => true
		case head :: Nil => true
		case head :: tails => head == tails.head && allEquals(tails)
	}

	def formatDate(time: Long): String =  {
		val simpleFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
		simpleFormat.format(new Date(time))
	}

	def formatDate(date: Date): String =  {
		val simpleFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
		simpleFormat.format(date)
	}

}
