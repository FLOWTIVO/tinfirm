package com.flowtivo.tinfirm.company.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.CompaniesApi;
import com.flowtivo.tinfirm.shared.api.model.Company;
import com.flowtivo.tinfirm.shared.api.model.CompanyPage;
import com.flowtivo.tinfirm.shared.api.model.CompanyUpdateRequest;

@RestController
class CompaniesController implements CompaniesApi {

    private final CompanyService companyService;

    CompaniesController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @Override
    public ResponseEntity<CompanyPage> listCompanies(Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(companyService.list(limit, cursor));
    }

    @Override
    public ResponseEntity<Company> getCompany(UUID companyId) {
        return ResponseEntity.ok(companyService.get(companyId));
    }

    @Override
    public ResponseEntity<Company> updateCompany(UUID companyId, CompanyUpdateRequest request) {
        return ResponseEntity.ok(companyService.rename(companyId, request));
    }
}
