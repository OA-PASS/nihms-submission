/*
 * Copyright 2018 Johns Hopkins University
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
package org.dataconservancy.pass.deposit.messaging.service;

import org.dataconservancy.pass.deposit.assembler.PackageOptions.Archive;
import org.dataconservancy.pass.deposit.assembler.PackageOptions.Compression;
import org.dataconservancy.pass.deposit.assembler.PackageOptions.Spec;
import org.dataconservancy.pass.deposit.assembler.PackageStream;
import org.dataconservancy.pass.deposit.builder.fs.FilesystemModelBuilder;
import org.dataconservancy.pass.deposit.builder.fs.SharedSubmissionUtil;
import org.dataconservancy.pass.deposit.transport.Transport;
import org.dataconservancy.pass.deposit.transport.TransportResponse;
import org.dataconservancy.pass.deposit.transport.TransportSession;
import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.deposit.assembler.dspace.mets.DspaceMetsAssembler;
import org.dataconservancy.pass.deposit.assembler.shared.AbstractAssembler;
import org.dataconservancy.pass.deposit.assembler.shared.BaseAssemblerIT;
import org.dataconservancy.pass.deposit.messaging.model.Packager;
import org.dataconservancy.pass.deposit.messaging.model.Registry;
import org.dataconservancy.pass.deposit.transport.sword2.Sword2DepositReceiptResponse;
import org.dataconservancy.pass.model.Deposit;
import org.dataconservancy.pass.model.Repository;
import org.dataconservancy.pass.model.RepositoryCopy;
import org.dataconservancy.pass.model.Submission;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.dataconservancy.pass.model.Deposit.DepositStatus.ACCEPTED;
import static org.dataconservancy.pass.model.Deposit.DepositStatus.SUBMITTED;
import static org.dataconservancy.pass.model.RepositoryCopy.CopyStatus.COMPLETE;
import static org.dataconservancy.pass.model.RepositoryCopy.CopyStatus.IN_PROGRESS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Elliot Metsger (emetsger@jhu.edu)
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {"spring.jms.listener.auto-startup=false"})
public class SubmittedStatusHandlingIT extends BaseAssemblerIT {

    @Autowired
    private DspaceMetsAssembler dspaceMetsAssembler;

    @Autowired
    private Registry<Packager> packagerRegistry;

    @Autowired
    private PassClient passClient;

    @Autowired
    private DepositTaskHelper underTest;

    @Autowired
    private FilesystemModelBuilder fsModelBuilder;

    private Deposit toUpdate;

    @Override
    public void setUp() throws Exception {

        submissionUtil = new SharedSubmissionUtil(fsModelBuilder);

        // Step 1: Create a PackageStream

        mbf = BaseAssemblerIT.metadataBuilderFactory();
        rbf = BaseAssemblerIT.resourceBuilderFactory();

        submission = submissionUtil.asDepositSubmission(URI.create("fake:submission3"));

        Packager packager = packagerRegistry.get("JScholarship");
        PackageStream stream = packager
                .getAssembler()
                .assemble(submission, packager.getAssemblerOptions());

        // Step 2: Deposit the Package to DSpace.

        Transport transport = packager.getTransport();
        TransportSession session = transport.open(packager.getConfiguration());
        TransportResponse tr = session.send(stream, packager.getConfiguration());
        if (!tr.success()) {
            if (tr.error() instanceof RuntimeException) {
                throw (RuntimeException) tr.error();
            }

            throw new RuntimeException(tr.error());
        }

        assertTrue(tr.success());
        assertTrue(tr instanceof Sword2DepositReceiptResponse);
        Sword2DepositReceiptResponse swordReceiptResponse = (Sword2DepositReceiptResponse) tr;

        // 2a. Keep a reference to the SWORD Statement

        URI swordStatement = swordReceiptResponse.getReceipt().getAtomStatementLink().getIRI().toURI();

        // 2.b Keep a reference to the DSpace Item URL

        URI dspaceItem = swordReceiptResponse.getReceipt().getSplashPageLink().getIRI().toURI();

        // Step 3: Manufacture a Deposit and RepositoryCopy for this test (normally created by the SubmissionProcessor)

        Submission submissionResource = new Submission();

        Deposit deposit = new Deposit();
        deposit.setDepositStatusRef(swordStatement.toString());
        deposit.setDepositStatus(SUBMITTED);
        deposit.setSubmission(submissionResource.getId());

        Repository repo = new Repository();
        repo.setName("JScholarship");
        repo.setRepositoryKey("JScholarship");

        repo = passClient.createAndReadResource(repo, Repository.class);

        RepositoryCopy rc = new RepositoryCopy();
        rc.setCopyStatus(RepositoryCopy.CopyStatus.IN_PROGRESS);
        rc.setAccessUrl(dspaceItem);
        rc.setExternalIds(Collections.singletonList(dspaceItem.toString()));

        submissionResource = passClient.createAndReadResource(submissionResource, Submission.class);

        rc = passClient.createAndReadResource(rc, RepositoryCopy.class);

        deposit.setRepositoryCopy(rc.getId());
        deposit.setSubmission(submissionResource.getId());
        deposit.setRepository(repo.getId());

        toUpdate = passClient.createAndReadResource(deposit, Deposit.class);
    }

    @Override
    protected AbstractAssembler assemblerUnderTest() {
        return dspaceMetsAssembler;
    }

    @Override
    protected Map<String, Object> getOptions() {
        return new HashMap<String, Object>() {
            {
                // TODO: checksums?  check this IT to see what happens
                put(Spec.KEY, DspaceMetsAssembler.SPEC_DSPACE_METS);
                put(Archive.KEY, Archive.OPTS.ZIP);
                put(Compression.KEY, Compression.OPTS.ZIP);
            }
        };
    }

    @Override
    protected void verifyStreamMetadata(PackageStream.Metadata metadata) {
        // no-op, we don't care.  we are simply re-using the logic in BaseAssemblerIT to
        // produce a package
    }

    @Test
    public void processStatusFromSubmittedToAccepted() throws Exception {
        assertEquals(SUBMITTED, toUpdate.getDepositStatus());
        assertEquals(IN_PROGRESS, passClient.readResource(toUpdate.getRepositoryCopy(), RepositoryCopy.class)
                .getCopyStatus());

        underTest.processDepositStatus(toUpdate.getId());

        Deposit deposit = passClient.readResource(toUpdate.getId(), Deposit.class);
        RepositoryCopy repoCopy = passClient.readResource(deposit.getRepositoryCopy(), RepositoryCopy.class);

        assertEquals(ACCEPTED, deposit.getDepositStatus());
        assertEquals(COMPLETE, repoCopy.getCopyStatus());
    }
}
