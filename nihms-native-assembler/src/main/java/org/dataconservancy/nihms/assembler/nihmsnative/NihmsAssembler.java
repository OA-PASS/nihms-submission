/*
 * Copyright 2017 Johns Hopkins University
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

package org.dataconservancy.nihms.assembler.nihmsnative;

import org.dataconservancy.nihms.assembler.PackageStream;
import org.dataconservancy.nihms.model.DepositSubmission;
import org.dataconservancy.nihms.assembler.MetadataBuilder;
import org.dataconservancy.pass.deposit.assembler.shared.AbstractAssembler;
import org.dataconservancy.pass.deposit.assembler.shared.MetadataBuilderFactory;
import org.dataconservancy.pass.deposit.assembler.shared.ResourceBuilderFactory;
import org.springframework.core.io.Resource;

import java.util.List;

public class NihmsAssembler extends AbstractAssembler {

    private static final String ERR_MAPPING_LOCATION = "Unable to resolve the location of a submitted file ('%s') to a Spring Resource type.";

    private static final String FILE_PREFIX = "file:";

    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final String WILDCARD_CLASSPATH_PREFIX = "classpath*:";

    private static final String HTTP_PREFIX = "http:";

    private static final String HTTPS_PREFIX = "https:";

    public NihmsAssembler(MetadataBuilderFactory mbf, ResourceBuilderFactory rbf) {
        super(mbf, rbf);
    }

    @Override
    protected PackageStream createPackageStream(DepositSubmission submission, List<Resource> custodialResources, MetadataBuilder mb, ResourceBuilderFactory rbf) {
        return new NihmsZippedPackageStream(submission, custodialResources, mb, rbf);
    }

}
