/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.yahcli.commands.contract;

import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.commands.contract.subcommands.DecompileContractCommand;
import com.hedera.services.yahcli.commands.contract.subcommands.DumpRawContractStateCommand;
import com.hedera.services.yahcli.commands.contract.subcommands.DumpRawContractsCommand;
import com.hedera.services.yahcli.commands.contract.subcommands.ResolveSelectorCommand;
import com.hedera.services.yahcli.commands.contract.subcommands.SummarizeSignedStateFileCommand;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "contract",
        subcommands = {
            CommandLine.HelpCommand.class,
            SummarizeSignedStateFileCommand.class,
            DumpRawContractsCommand.class,
            DumpRawContractStateCommand.class,
            DecompileContractCommand.class,
            ResolveSelectorCommand.class
        },
        description = "Dealing with contracts")
public class ContractCommand implements Callable<Integer> {

    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        throw new CommandLine.ParameterException(
                yahcli.getSpec().commandLine(), "Please specify a contract subcommand!");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}
