//
// License: https://github.com/webintrinsics/clusterlite/blob/master/LICENSE
//

package org.clusterlite

import java.io.{ByteArrayOutputStream, File}
import java.net.InetAddress
import java.util.NoSuchElementException

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.ObjectMapper
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import com.eclipsesource.schema.{FailureExtensions, SchemaFormat, SchemaType, SchemaValidator}
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import play.api.libs.json._


import scala.util.Try

trait AllCommandOptions {
    val isDryRun: Boolean
}

case class AnyCommandWithoutOptions(isDryRun: Boolean = false) extends AllCommandOptions {
    override def toString: String = {
        s"""
           |#    dry-run=$isDryRun
           |#""".stripMargin
    }
}

case class InstallCommandOptions(
    isDryRun: Boolean,
    token: String,
    name: String,
    seedsArg: String,
    publicAddress: String = "",
    dataDirectory: String = "/var/clusterlite") extends AllCommandOptions {
    override def toString: String = {
        s"""
          |#    dry-run=$isDryRun
          |#    token=$token
          |#    name=$name
          |#    seeds=$seedsArg
          |#    public-address=$publicAddress
          |#    data-directory=$dataDirectory
          |#""".stripMargin
    }

    lazy val seeds: Vector[String] = seedsArg.split(',').toVector
}

case class ApplyCommandOptions(
    isDryRun: Boolean = false,
    config: String = "") extends AllCommandOptions {
    override def toString: String = {
        s"""
           |#    dry-run=$isDryRun
           |#    config=$config
           |#""".stripMargin
    }
}

class ErrorException(msg: String) extends Exception(msg)
class ParseException(msg: String = "") extends Exception(msg)
class EnvironmentException(msg: String) extends Exception(msg)
class PrerequisitesException(msg: String) extends Exception(msg)
class ConfigException(errors: JsArray)
    extends Exception(s"Errors:\n${Json.prettyPrint(errors)}\n" +
        "Try --help for more information.")

class Main(env: Env) {

    private val operationId = env.get(Env.ClusterliteId)
    private val dataDir: String = env.getOrElse(Env.ClusterliteData, s"/data/clusterlite/$operationId")
    private val config: JsValue = Json.parse(Utils.loadFromFile(dataDir, "clusterlite.json"))
    private val network: JsValue = Json.parse(Utils.loadFromFile(dataDir, "weave.json"))
    private val placements: JsValue = Json.parse(Utils.loadFromFile(dataDir, "placements.json"))
    private val containers: JsValue = Json.parse(Utils.loadFromFile(dataDir, "docker.json"))
    private val placementsFile: Option[String] = Utils.loadFromFileIfExists(dataDir, "placements-new.json")
    private val volume: Option[String] = (config \ "volume").asOpt[String]

    private var runargs: Vector[String] = Nil.toVector

    private def run(args: Vector[String]): String = {
        runargs = args
        val command = args.headOption.getOrElse("help")
        val opts = args.drop(1)
        doCommand(command, opts)
    }

    private def doCommand(command: String, opts: Vector[String]): String = { //scalastyle:ignore

        def run[A <: AllCommandOptions](parser: scopt.OptionParser[A], d: A, action: (A) => String) = {
            parser.parse(opts, d).fold(throw new ParseException())(c => {
                val result = action(c)
                if (c.isDryRun) {
                    wrapEcho(result)
                } else {
                    result
                }
            })
        }

        command match {
            case "help" | "--help" | "-help" | "-h" =>
                val d = AnyCommandWithoutOptions()
                val parser = new scopt.OptionParser[AnyCommandWithoutOptions]("clusterlite help") {
                    help("help")
                    opt[Unit]("dry-run")
                        .action((x, c) => c.copy(isDryRun = true))
                        .maxOccurs(1)
                        .text("If set, the action will not initiate an action\n" +
                            s"but will print the script of intended actions. Default ${d.isDryRun}")
                }
                run(parser, d, helpCommand)
            case "version" | "--version" | "-version" | "-v" =>
                val d = AnyCommandWithoutOptions()
                val parser = new scopt.OptionParser[AnyCommandWithoutOptions]("clusterlite version") {
                    help("help")
                    opt[Unit]("dry-run")
                        .action((x, c) => c.copy(isDryRun = true))
                        .maxOccurs(1)
                        .text("If set, the action will not initiate an action\n" +
                            s"but will print the script of intended actions. Default ${d.isDryRun}")
                }
                run(parser, d, versionCommand)
            case "install" =>
                val hostInterface = if (env.get(Env.HostnameI) == "127.0.0.1") {
                    env.get(Env.Ipv4Addresses).split(" ")
                        .toVector
                        .filter(i => i != env.get(Env.HostnameI))
                        .lastOption.getOrElse(env.get(Env.HostnameI))
                } else {
                    env.get(Env.HostnameI)
                }
                val d = InstallCommandOptions(isDryRun = false, "", env.get(Env.Hostname), hostInterface)
                val parser = new scopt.OptionParser[InstallCommandOptions]("clusterlite install") {
                    help("help")
                    opt[Unit]("dry-run")
                        .action((x, c) => c.copy(isDryRun = true))
                        .maxOccurs(1)
                        .text("If set, the action will not initiate an action\n" +
                            s"but will print the script of intended actions. Default ${d.isDryRun}")
                    opt[String]("name")
                        .action((x, c) => c.copy(name = x))
                        .maxOccurs(1)
                        .text(s"Name of a node. It can be any string but it should be unique within the scope of the cluster. Default ${d.name}")
                    opt[String]("token")
                        .required()
                        .maxOccurs(1)
                        .validate(c => if (c.length < 16) {
                            failure("token parameter should be at least 16 characters long")
                        } else {
                            success
                        })
                        .action((x, c) => c.copy(token = x))
                        .text(s"Cluster secret key. It is used for inter-node traffic encryption. Default ${d.token}")
                    opt[String]("seeds")
                        .action((x, c) => c.copy(seedsArg = x))
                        .maxOccurs(1)
                        .text("IP addresses or hostnames of seed nodes separated by comma. " +
                            "This should be the same value for all nodes joining the cluster. " +
                            "It is NOT necessary to enumerate all nodes in the cluster as seeds. " +
                            "For high-availability it should include 3 or 5 nodes. " +
                            s"Default ${d.seedsArg}")
                    opt[String]("data-directory")
                        .action((x, c) => c.copy(dataDirectory = x))
                        .maxOccurs(1)
                        .validate(c => if (c.isEmpty) {
                            failure("data-directory should be non empty path")
                        } else {
                            success
                        })
                        .text(s"Path to a directory where the node will persist data. Default ${d.dataDirectory}")
                    opt[String]("public-address")
                        .action((x, c) => c.copy(publicAddress = x))
                        .maxOccurs(1)
                        .text("Public IP address of the node, if exists or requires exposure. " +
                            "This can be assigned later with help of set command. Default not assigned")
                }
                run(parser, d, installCommand)
            case "uninstall" =>
                val d = AnyCommandWithoutOptions()
                val parser = new scopt.OptionParser[AnyCommandWithoutOptions]("clusterlite uninstall") {
                    help("help")
                    opt[Unit]("dry-run")
                        .action((x, c) => c.copy(isDryRun = true))
                        .maxOccurs(1)
                        .text("If set, the action will not initiate an action\n" +
                            s"but will print the script of intended actions. Default ${d.isDryRun}")
                }
                run(parser, d, uninstallCommand)
            case "apply" =>
                val d = ApplyCommandOptions()
                val parser = new scopt.OptionParser[ApplyCommandOptions]("clusterlite apply") {
                    help("help")
                    opt[Unit]("dry-run")
                        .action((x, c) => c.copy(isDryRun = true))
                        .maxOccurs(1)
                        .text("If set, the action will not initiate an action\n" +
                            s"but will print the script of intended actions. Default ${d.isDryRun}")
                    opt[String]("config")
                        .required()
                        .maxOccurs(1)
                        .validate(c => {
                            placementsFile.fold(failure("config parameter points to non-existing or non-accessible file")){
                                _ => success
                            }
                        })
                        .action((x, c) => c.copy(config = x))
                        .text("Configuration to apply")
                }
                run(parser, d, applyCommand)
            case i: String =>
                helpCommand(AnyCommandWithoutOptions())
                throw new ParseException(s"Error: $i is unknown command\n" +
                    "Try --help for more information.")
        }
    }

    private def installCommand(parameters: InstallCommandOptions): String = {
        // TODO allow a client to pick alternative ip ranges for weave
        // TODO update existing peers with new peers added:
        // TODO see documentation about, investigate if it is really needed:
        // TODO For maximum robustness, you should distribute an updated /etc/sysconfig/weave file including the new peer to all existing peers.

        if (parameters.seedsArg.isEmpty) {
            throw new ParseException("Error: seeds parameter should not be empty\n" +
                "Try --help for more information.")
        }

        val currentSeedId: Option[Int] = {
            parameters.seeds
                .zipWithIndex
                .flatMap(a => {
                    Try(InetAddress.getAllByName(a._1).toVector)
                        .getOrElse(throw new ParseException(
                            "Error: failure to resolve all hostnames for seeds parameter\n" +
                            "Try --help for more information."))
                        .map(b=> b.getHostAddress -> a._2)
                })
                .find(a => env.get(Env.Ipv4Addresses).split(" ").contains(a._1) ||
                    env.get(Env.Ipv6Addresses).split(" ").contains(a._1))
                .map(a => a._2 + 1)
        }

        val weaveVersion: String = {
            val weaveVersionString = env.get(Env.WeaveVersion)
            weaveVersionString.drop("SCRIPT_VERSION=\"".length).dropRight(1)
        }

        val weaveDownloadRequired: Boolean = {
            val wv = weaveVersion
                .split('.')
                .map(i => Try(i.toLong).getOrElse(0L))
                .take(3)
                .reverse
                .zipWithIndex.map(i => i._1 << (i._2 * 8))
                .sum
            val wvRequired = "1.9.5".split('.').map(i => i.toLong)
                .reverse
                .zipWithIndex.map(i => i._1 << (i._2 * 8))
                .sum
            wv < wvRequired
        }

        // Plan at least 3 seeds for any case,
        // even if a cluster will have only 1 node deployed (initially or ever).
        // It will work because uniform dynamic cluster does not require seeds to reach a consensus.
        // It will be possible to add more seeds (up to 3 in total) later.
        // User shall be required to supply the same combination of seeds parameter for each new node
        val totalSeeds = Math.max(parameters.seeds.length, 3)

        val template = volume.fold("install.sh") { _ => "install-empty.sh" }
        Utils.loadFromResource(template)
            .unfold("__WEAVE_DOWNLOAD_PART__", {
                if (weaveDownloadRequired) {
                    Utils.loadFromResource("install-weave-download.sh")
                } else {
                    s"""    echo \"__LOG__ weave ($weaveVersion) detected, no download required\""""
                }
            })
            .unfold("__WEAVE_SEED_NAME__", currentSeedId.fold(""){
                s => s"--name ::$s"
            })
            .unfold("__WEAVE_ALL_SEEDS__", Seq.range(1, totalSeeds + 1).map(i => s"::$i").mkString(","))
            .unfold("__CONFIG__", "'''" + Json.stringify(Json.obj(
                "name" -> parameters.name,
                "volume" -> parameters.dataDirectory,
                "token" -> parameters.token,
                "seeds" -> parameters.seedsArg,
                "publicIp" -> parameters.publicAddress,
                "seedId" -> currentSeedId.getOrElse(throw new NotImplementedError(
                    "autoscale nodes are not supported yet, please enumerate all peers as seeds in the cluster"))
            )) + "'''")
            .unfold("__ENVIRONMENT__", env.toString)
            .unfold("__TOKEN__", parameters.token)
            .unfold("__NAME__", parameters.name)
            .unfold("__SEEDS__", parameters.seedsArg)
            .unfold("__PARSED_ARGUMENTS__", parameters.toString)
            .unfold("__COMMAND__", s"clusterlite ${runargs.mkString(" ")}")
            .unfold("__PUBLIC_ADDRESS__", parameters.publicAddress)
            .unfold("__VOLUME__", parameters.dataDirectory)
            .unfold("__LOG__", "[clusterlite install]")
    }

    private def uninstallCommand(parameters: AnyCommandWithoutOptions): String = {
        // TODO think about dropping loaded images and finished containers

        // as per documentation add 'weave forget' command when remote execution is possible
        // https://www.weave.works/docs/net/latest/operational-guide/uniform-fixed-cluster/
        val template = volume.fold("uninstall-empty.sh") { _ => "uninstall.sh" }
        Utils.loadFromResource(template)
            .unfold("\r\n", "\n")
            .unfold("__ENVIRONMENT__", env.toString)
            .unfold("__PARSED_ARGUMENTS__", parameters.toString)
            .unfold("__COMMAND__", s"clusterlite ${runargs.mkString(" ")}")
            .unfold("__VOLUME__", volume.get)
            .unfold("__LOG__", "[clusterlite uninstall]")
    }

    private def applyCommand(parameters: ApplyCommandOptions): String = {
        ensureInstalled()

        val pn = placementsNew
        println(Json.prettyPrint(pn))

        val template = "apply.sh"
        Utils.loadFromResource(template)
            .unfold("\r\n", "\n")
            .unfold("__PARSED_ARGUMENTS__", parameters.toString)
            .unfold("__COMMAND__", s"clusterlite ${runargs.mkString(" ")}")
            .unfold("__VOLUME__", volume.get)
            .unfold("__LOG__", "[clusterlite uninstall]")
    }

    private def helpCommand(parameters: AllCommandOptions): String = {
        val used = parameters
        // TODO implement
        //        apply     Aligns current cluster state with configuration:
        //            starts newly added machines, terminates removed machines and volumes
        """Usage: clusterlite help
          |       clusterlite --help
          |       clusterlite <command> --help
          |
          |Commands:
          |       help      Prints this message
          |       version   Prints version information
          |       install   Provisions the current host and joins the cluster
          |       uninstall Leaves the cluster, uninstalls processes and data
          |       apply     Sets new configuration for services and starts them
          |""".stripMargin
    }

    private def versionCommand(parameters: AllCommandOptions): String = {
        val used = Option(parameters)
        "Webintrinsics Clusterlite, version 0.1.0"
    }

    private implicit class RichString(origin: String) {
        def unfold(pattern: String, replacement: => String): String = {
            if (origin.contains(pattern)) {
                // touch replacement lazily only if needed (found something to replace)
                origin.replace(pattern, replacement)
            } else {
                origin
            }
        }
    }

    private def ensureInstalled(): Unit = {
        volume.getOrElse(throw new PrerequisitesException(
            "Error: clusterlite is not installed\n" +
            "Try 'install --help' for more information."))
    }

    private lazy val placementsNew: JsObject = {
        val parsedConfigAsJson = try {
            val yamlReader = new ObjectMapper(new YAMLFactory())
            val obj = yamlReader.readValue(placementsFile.get, classOf[Object])
            val jsonWriter = new ObjectMapper()
            val json = jsonWriter.writeValueAsString(obj)
            Json.parse(json)
        } catch {
            case ex: Throwable =>
                val message = ex.getMessage.replace("in 'reader', line ", "at line ")
                        .replaceAll(" at \\[Source: java.io.StringReader@.*", "")
                throw new ParseException(
                s"$message\n" +
                "Error: config parameter refers to invalid YAML file\n" +
                "Try --help for more information.")
        }

        val schema = Json.parse(Utils.loadFromResource("schema.json")).as[JsObject]
        val schemaType = Json.fromJson[SchemaType](schema).get
        SchemaValidator()
            .validate(schemaType, parsedConfigAsJson)
            .fold(invalid = errors => throw new ConfigException(errors.toJson),
                valid = result => result.as[JsObject])
    }

    private def wrapEcho(str: String): String = {
        s"\n$str\n"
    }
}

object Main extends App {
    System.exit(apply(Env()))

    def apply(env: Env): Int = {
        var result = 1
        try {
            val app = new Main(env)
            System.out.print(app.run(args.toVector))
            System.out.print("\n")
            result = 0
        } catch {
            case ex: ErrorException =>
                System.out.print(s"Error: ${ex.getMessage}\n" +
                    "Try --help for more information." +
                    "[clusterlite] failure: unclassified exception\n")
            case ex: ParseException =>
                if (ex.getMessage.isEmpty) {
                    System.out.print("[clusterlite] failure: invalid argument(s)\n")
                } else {
                    System.out.print(s"${ex.getMessage}\n[clusterlite] failure: invalid arguments\n")
                }
            case ex: ConfigException =>
                System.out.print(s"${ex.getMessage}\n[clusterlite] failure: invalid configuration file\n")
            case ex: PrerequisitesException =>
                System.out.print(s"${ex.getMessage}\n[clusterlite] failure: prerequisites not satisfied\n")
            case ex: Throwable =>
                val out = new ByteArrayOutputStream
                Console.withErr(out) {
                    ex.printStackTrace()
                }
                System.out.print(s"$out\n[clusterlite] failure: internal error, " +
                    "please report to https://github.com/webintrinsics/clusterlite\n")
        }
        result
    }
}
