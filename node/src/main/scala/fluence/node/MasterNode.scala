/*
 * Copyright 2018 Fluence Labs Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fluence.node
import java.nio.file.{Path, Paths}

import cats.effect.{ConcurrentEffect, ExitCode, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._
import fluence.node.eth.DeployerContract
import fluence.node.solvers.{SolverParams, SolversPool}
import fluence.node.tendermint.{ClusterData, KeysPath}

/**
 * Represents a MasterNode process. Takes cluster forming events from Ethereum, and spawns new solvers to serve them.
 *
 * @param masterKeys Tendermint keys
 * @param nodeConfig Tendermint/Fluence master node config
 * @param contract DeployerContract to interact with
 * @param pool Solvers pool to launch solvers in
 * @param path Path to store all the MasterNode's data in
 * @param ce Concurrent effect, used to subscribe to Ethereum events
 */
case class MasterNode[F[_]: ConcurrentEffect](
  masterKeys: KeysPath[F],
  nodeConfig: NodeConfig,
  contract: DeployerContract,
  pool: SolversPool[F],
  path: Path
)(implicit F: Sync[F])
    extends slogging.LazyLogging {

  private def getCodePath(codeName: String): String = {
    val examplesPath = getClass.getClassLoader.getResource("examples").getPath
    if (examplesPath.contains(".jar")) {
      // TODO: REMOVE. It's an ad-hoc fix to avoid copying resources from jar in case of `sbt runMain`
      Paths.get("./statemachine/docker/examples/vmcode-" + codeName).toAbsolutePath.toString
    } else {
      Paths
        .get(getClass.getClassLoader.getResource("examples").getPath + "/vmcode-" + codeName)
        .toAbsolutePath
        .toString
    }
  }

  // Converts ClusterData into SolverParams which is ready to run
  private val clusterDataToParams: fs2.Pipe[F, (ClusterData, Path), SolverParams] =
    _.map {
      case (clusterData, solverTendermintPath) ⇒
        SolverParams(
          clusterData,
          solverTendermintPath.toString,
          // TODO fetch (from swarm) & cache
          getCodePath(clusterData.code)
        )
    }

  // Writes node info & master keys to tendermint directory
  private val writeConfigAndKeys: fs2.Pipe[F, ClusterData, (ClusterData, Path)] =
    _.evalMap(
      clusterData =>
        for {
          _ ← F.delay { logger.info("joining cluster '{}' as node {}", clusterData.clusterName, clusterData.nodeIndex) }

          solverTendermintPath ← F.delay(
            path.resolve(s"${clusterData.nodeInfo.clusterName}-${clusterData.nodeInfo.node_index}")
          )

          _ ← clusterData.nodeInfo.writeTo(solverTendermintPath)
          _ ← masterKeys.copyKeysToSolver(solverTendermintPath)
        } yield {
          logger.info("node info written to {}", solverTendermintPath)
          clusterData -> solverTendermintPath
      }
    )

  /**
   * Runs MasterNode. Returns when contract.getAllNodeClusters is exhausted
   * TODO: add a way to cleanup, e.g. unsubscribe and stop
   */
  val run: F[ExitCode] =
    contract
      .getAllNodeClusters(nodeConfig)
      .through(writeConfigAndKeys)
      .through(clusterDataToParams)
      .evalTap { params ⇒
        logger.info("Running solver `{}`", params)

        pool.run(params).map(newlyAdded ⇒ logger.info(s"solver run (newly=$newlyAdded) {}", params))
      }
      .drain // drop the results, so that demand on events is always provided
      .onFinalize(F.delay(logger.info("subscription finalized")))
      .compile // Compile to a runnable, in terms of effect IO
      .drain // Switch to IO[Unit]
      .map(_ ⇒ ExitCode.Success)

}
