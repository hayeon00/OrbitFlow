document.addEventListener('DOMContentLoaded', () => {

    // ============================================
    // 0. 기본 변수 및 공통 함수
    // ============================================

    const modal = document.getElementById('exceptionRuleModal');
    const editModal = document.getElementById('editExceptionRuleModal');

    const form = document.getElementById('exceptionRuleForm');
    const editForm = document.getElementById('editExceptionRuleForm');

    const $ = id => document.getElementById(id);

    const value = id => $(id)?.value || '';

    const setValue = (id, v) => {
        const el = $(id);
        if (el) el.value = v ?? '';
    };

    const time5 = v => v ? String(v).substring(0, 5) : '';

    const toApiTime = v => v ? `${time5(v)}:00` : null;

    let exceptionRulesList = [];

    // HTML로 삽입되는 서버 문자열을 이스케이프
    const escapeHtml = v => String(v ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');

    const showApiError = async (res, fallback) => {
        const body = await res.json().catch(() => null);
        sweetError(body?.message || fallback);
    };

    // ============================================
    // 1. 점심 휴게시간 계산
    // ============================================

    function toMinutes(time) {
        if (!/^\d{2}:\d{2}$/.test(time || '')) {
            return null;
        }

        const [hour, minute] = time.split(':').map(Number);

        if (hour > 23 || minute > 59) {
            return null;
        }

        return hour * 60 + minute;
    }

    function calculateBreakMinutes(start, end) {
        const startMinutes = toMinutes(start);
        const endMinutes = toMinutes(end);

        if (startMinutes === null || endMinutes === null) {
            return null;
        }

        if (endMinutes <= startMinutes) {
            return null;
        }

        return endMinutes - startMinutes;
    }

    function bindLunchDuration(startId, endId, minutesId) {
        const refresh = () => {
            const minutes = calculateBreakMinutes(
                value(startId),
                value(endId)
            );

            setValue(
                minutesId,
                minutes === null ? '' : minutes
            );
        };

        $(startId)?.addEventListener('input', refresh);
        $(endId)?.addEventListener('input', refresh);
    }

    bindLunchDuration(
        'defaultBreakStartTime',
        'defaultBreakEndTime',
        'defaultBreakMinutes'
    );

    bindLunchDuration(
        'exceptionBreakStartTime',
        'exceptionBreakEndTime',
        'exceptionBreakMinutes'
    );

    bindLunchDuration(
        'editExceptionBreakStartTime',
        'editExceptionBreakEndTime',
        'editExceptionBreakMinutes'
    );

    function getLunchPayload(
        startId,
        endId,
        minutesId,
        workStart,
        workEnd,
        required = true
    ) {
        const lunchStart = value(startId);
        const lunchEnd = value(endId);

        if (!lunchStart && !lunchEnd && !required) {
            return {
                breakStartTime: null,
                breakEndTime: null,
                breakMinutes: null
            };
        }

        if (!lunchStart || !lunchEnd) {
            sweetWarning('점심 시작·종료시간을 모두 입력해주세요.');
            return null;
        }

        const minutes = calculateBreakMinutes(
            lunchStart,
            lunchEnd
        );

        if (minutes === null || minutes > 480) {
            sweetWarning('점심 휴게시간 설정이 올바르지 않습니다.');
            return null;
        }

        if (!workStart || !workEnd || workStart >= workEnd) {
            sweetWarning('출퇴근시간을 확인해주세요.');
            return null;
        }

        if (lunchStart < workStart || lunchEnd > workEnd) {
            sweetWarning('점심시간은 근무시간 안에 있어야 합니다.');
            return null;
        }

        setValue(minutesId, minutes);

        return {
            breakStartTime: toApiTime(lunchStart),
            breakEndTime: toApiTime(lunchEnd),
            breakMinutes: minutes
        };
    }

    // ============================================
    // 2. 회사 기본 근태 규칙 조회
    // ============================================

    async function loadDefaultRule() {
        try {
            const res = await apiFetch('/api/admin/rules/default');

            if (!res.ok) {
                await showApiError(res, '기본 규칙 조회에 실패했습니다.');
                return;
            }

            const response = await res.json();
            const rule = response.data;

            if (!rule) return;

            setValue('defaultStartTime', time5(rule.defaultStartTime));
            setValue('defaultEndTime', time5(rule.defaultEndTime));

            setValue(
                'defaultBreakMinutes',
                rule.defaultBreakMinutes ?? ''
            );

            setValue(
                'defaultBreakStartTime',
                time5(rule.breakStartTime)
            );

            setValue(
                'defaultBreakEndTime',
                time5(rule.breakEndTime)
            );

        } catch (error) {
            console.error('기본 규칙 로드 실패:', error);
        }
    }

    // ============================================
    // 3. 회사 기본 근태 규칙 저장
    // ============================================

    $('saveDefaultRuleBtn')?.addEventListener('click', async () => {

        const workStart = value('defaultStartTime');
        const workEnd = value('defaultEndTime');

        if (!workStart || !workEnd) {
            sweetWarning('출퇴근시간을 입력해주세요.');
            return;
        }

        if (workStart >= workEnd) {
            sweetWarning('퇴근시간은 출근시간보다 늦어야 합니다.');
            return;
        }

        const lunch = getLunchPayload(
            'defaultBreakStartTime',
            'defaultBreakEndTime',
            'defaultBreakMinutes',
            workStart,
            workEnd,
            true
        );

        if (!lunch) return;

        const data = {
            defaultStartTime: toApiTime(workStart),
            defaultEndTime: toApiTime(workEnd),
            defaultBreakMinutes: lunch.breakMinutes,
            breakStartTime: lunch.breakStartTime,
            breakEndTime: lunch.breakEndTime
        };

        try {
            const res = await apiFetch('/api/admin/rules/default', {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(data)
            });

            if (res.ok) {
                sweetSuccess('회사 기본 근태 규칙이 저장되었습니다.');
                await loadDefaultRule();
            } else {
                await showApiError(
                    res,
                    '기본 규칙 저장에 실패했습니다.'
                );
            }

        } catch (error) {
            console.error(error);
            sweetError('통신 중 오류가 발생했습니다.');
        }
    });

    // ============================================
    // 4. 회사별 반차 정책 조회
    // ============================================

    async function loadLeaveTimePolicy() {
        try {
            const res = await apiFetch(
                '/api/admin/rules/leave-time-policy'
            );

            if (!res.ok) {
                console.warn('반차 정책 조회 실패:', res.status);
                return;
            }

            const response = await res.json();
            const policy = response.data;

            if (!policy) return;

            setValue(
                'morningHalfReturnTime',
                time5(policy.morningHalfReturnTime)
            );

            setValue(
                'afternoonHalfLeaveTime',
                time5(policy.afternoonHalfLeaveTime)
            );

        } catch (error) {
            console.error('반차 정책 조회 실패:', error);
        }
    }

    // ============================================
    // 5. 회사별 반차 정책 저장
    // ============================================

    $('saveLeaveTimePolicyBtn')?.addEventListener('click', async () => {

        const morning = value('morningHalfReturnTime');
        const afternoon = value('afternoonHalfLeaveTime');

        if (!morning || !afternoon) {
            sweetWarning('오전·오후 반차 시간을 모두 입력해주세요.');
            return;
        }

        const data = {
            morningHalfReturnTime: toApiTime(morning),
            afternoonHalfLeaveTime: toApiTime(afternoon)
        };

        try {
            const res = await apiFetch(
                '/api/admin/rules/leave-time-policy',
                {
                    method: 'PUT',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(data)
                }
            );

            if (res.ok) {
                sweetSuccess('회사별 반차 정책이 저장되었습니다.');
                await loadLeaveTimePolicy();
            } else {
                await showApiError(
                    res,
                    '반차 정책 저장에 실패했습니다.'
                );
            }

        } catch (error) {
            console.error(error);
            sweetError('통신 중 오류가 발생했습니다.');
        }
    });

    // ============================================
    // 6. 사원별 예외 규칙 목록 조회
    // ============================================

    async function loadExceptionRules() {
        try {
            const res = await apiFetch('/api/admin/rules/exception');

            if (!res.ok) {
                console.error('예외 규칙 목록 조회 실패:', res.status);
                return;
            }

            const response = await res.json();

            exceptionRulesList = response.data || [];

            exceptionRulesList.sort(
                (a, b) => b.overrideId - a.overrideId
            );

            renderExceptionRulesTable(exceptionRulesList);

        } catch (error) {
            console.error('예외 규칙 목록 로드 실패:', error);
        }
    }

    function renderExceptionRulesTable(rules) {
        const tbody = $('exceptionRulesTableBody');

        if (!tbody) return;

        if (!rules.length) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="8"
                        style="text-align:center; padding:20px;">
                        등록된 예외 규칙이 없습니다.
                    </td>
                </tr>
            `;
            return;
        }

        tbody.innerHTML = rules.map(rule => {

            const lunchText = (
                rule.breakStartTime && rule.breakEndTime
            )
                ? `${time5(rule.breakStartTime)} ~
                   ${time5(rule.breakEndTime)}
                   (${rule.breakMinutes ?? 0}분)`
                : (
                    rule.breakMinutes != null
                        ? `${rule.breakMinutes}분`
                        : '-'
                );

            return `
                <tr>
                    <td>
                        <strong>${escapeHtml(rule.employeeName)}</strong>
                        <br>
                        <small>${escapeHtml(rule.employeeNo)}</small>
                    </td>
                    <td>
                        ${escapeHtml(rule.validFrom)}
                        ~
                        ${escapeHtml(rule.validTo || '무기한')}
                    </td>
                    <td>${escapeHtml(time5(rule.startTime) || '-')}</td>
                    <td>${escapeHtml(time5(rule.endTime) || '-')}</td>
                    <td>${escapeHtml(lunchText)}</td>
                    <td>${escapeHtml(rule.reason)}</td>
                    <td>
                        ${escapeHtml(
                rule.appliedAt?.substring(0, 10) || '-'
            )}
                    </td>
                    <td>
                        <button type="button"
                                class="btn-edit"
                                data-id="${Number(rule.overrideId)}">
                            수정
                        </button>

                        <button type="button"
                                class="btn-delete"
                                data-id="${Number(rule.overrideId)}">
                            삭제
                        </button>
                    </td>
                </tr>
            `;
        }).join('');
    }

    // ============================================
    // 7. 예외 규칙 목록 버튼 이벤트
    // ============================================

    $('exceptionRulesTableBody')?.addEventListener('click', e => {

        const editBtn = e.target.closest('.btn-edit');
        const deleteBtn = e.target.closest('.btn-delete');

        if (editBtn) {
            openEditModal(editBtn.dataset.id);
        }

        if (deleteBtn) {
            deleteExceptionRule(deleteBtn.dataset.id);
        }
    });

    // ============================================
    // 8. 모달 공통 함수
    // ============================================

    function openModal(target) {
        if (!target) return;

        target.style.display = 'flex';
        document.body.style.overflow = 'hidden';
    }

    function closeModal(target) {
        if (!target) return;

        target.style.display = 'none';
        document.body.style.overflow = '';
    }

    function clearAllErrors() {
        document.querySelectorAll('.error-message')
            .forEach(el => {
                el.style.display = 'none';
            });
    }

    document.querySelectorAll('.close').forEach(btn => {
        btn.addEventListener('click', () => {
            closeModal(modal);
            closeModal(editModal);
        });
    });

    document.querySelectorAll('.close-modal').forEach(btn => {
        btn.addEventListener('click', () => {
            closeModal(modal);
            closeModal(editModal);
        });
    });

    window.addEventListener('click', event => {
        if (event.target === modal) closeModal(modal);
        if (event.target === editModal) closeModal(editModal);
    });

    // ============================================
    // 9. 글자 수 표시
    // ============================================

    function setupCharCount(textareaId, countId) {
        const textarea = $(textareaId);
        const count = $(countId);

        if (!textarea || !count) return;

        textarea.addEventListener('input', () => {
            const length = textarea.value.length;

            count.textContent = `${length} / 40`;
            count.classList.toggle('error', length >= 40);
        });
    }

    setupCharCount('reason', 'reasonCharCount');
    setupCharCount('editReason', 'editReasonCharCount');

    // ============================================
    // 10. 신규 예외 규칙 추가 모달
    // ============================================

    $('addExceptionRuleBtn')?.addEventListener('click', () => {

        form.reset();

        setValue('selectedEmployeeId', '');
        setValue('employeeSearchInput', '');

        $('selectedEmployeeInfo').style.display = 'none';
        $('employeeSearchResults').innerHTML = '';
        $('reasonCharCount').textContent = '0 / 40';

        // 회사 기본 점심시간을 초기값으로 사용
        setValue(
            'exceptionBreakStartTime',
            value('defaultBreakStartTime')
        );

        setValue(
            'exceptionBreakEndTime',
            value('defaultBreakEndTime')
        );

        const minutes = calculateBreakMinutes(
            value('exceptionBreakStartTime'),
            value('exceptionBreakEndTime')
        );

        setValue(
            'exceptionBreakMinutes',
            minutes ?? ''
        );

        clearAllErrors();
        openModal(modal);
    });

    // 적용 시작일을 변경하면 종료일 최소 날짜 설정
    $('validFrom')?.addEventListener('change', () => {

        const from = value('validFrom');
        const toInput = $('validTo');

        if (!toInput) return;

        toInput.min = from;

        if (toInput.value && toInput.value < from) {
            toInput.value = '';
        }
    });

    // ============================================
    // 11. 신규 예외 규칙 저장
    // ============================================

    form?.addEventListener('submit', async e => {

        e.preventDefault();

        const employeeId = value('selectedEmployeeId');
        const validFrom = value('validFrom');
        const validTo = value('validTo') || null;

        const startTime = value('exceptionStartTime');
        const endTime = value('exceptionEndTime');

        const reason = value('reason').trim();

        if (!employeeId || !validFrom ||
            !startTime || !endTime || !reason) {

            sweetWarning('필수 항목을 모두 입력해주세요.');
            return;
        }

        if (startTime >= endTime) {
            sweetWarning('퇴근시간은 출근시간보다 늦어야 합니다.');
            return;
        }

        if (validTo && validTo < validFrom) {
            sweetWarning('종료일은 시작일보다 빠를 수 없습니다.');
            return;
        }

        const lunch = getLunchPayload(
            'exceptionBreakStartTime',
            'exceptionBreakEndTime',
            'exceptionBreakMinutes',
            startTime,
            endTime,
            false
        );

        if (!lunch) return;

        const data = {
            employeeId: Number(employeeId),
            startTime: toApiTime(startTime),
            endTime: toApiTime(endTime),
            ...lunch,
            reason,
            validFrom,
            validTo
        };

        try {
            const res = await apiFetch(
                '/api/admin/rules/exception',
                {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(data)
                }
            );

            if (res.ok) {
                sweetSuccess('예외 규칙이 추가되었습니다.');
                closeModal(modal);
                await loadExceptionRules();
            } else {
                await showApiError(
                    res,
                    '예외 규칙 추가에 실패했습니다.'
                );
            }

        } catch (error) {
            console.error(error);
            sweetError('통신 중 오류가 발생했습니다.');
        }
    });

    // ============================================
    // 12. 기존 예외 규칙 수정 모달 열기
    // ============================================

    async function openEditModal(ruleId) {
        if (!ruleId) return;

        try {
            const res = await apiFetch(
                `/api/admin/rules/exception/${ruleId}`
            );

            if (!res.ok) {
                await showApiError(
                    res,
                    '예외 규칙 조회에 실패했습니다.'
                );
                return;
            }

            const response = await res.json();
            const rule = response.data;

            if (!rule) return;

            setValue('editRuleId', rule.overrideId);
            setValue('editValidFrom', rule.validFrom);
            setValue('editValidTo', rule.validTo || '');

            $('editValidTo').min = rule.validFrom;

            $('editEmployeeInfo').textContent =
                `${rule.employeeName} (${rule.employeeNo || ''})`;

            $('editOriginalValidTo').textContent =
                rule.validTo || '무기한';

            setValue(
                'editExceptionStartTime',
                time5(rule.startTime)
            );

            setValue(
                'editExceptionEndTime',
                time5(rule.endTime)
            );

            setValue(
                'editExceptionBreakStartTime',
                time5(rule.breakStartTime)
            );

            setValue(
                'editExceptionBreakEndTime',
                time5(rule.breakEndTime)
            );

            setValue(
                'editExceptionBreakMinutes',
                rule.breakMinutes ?? ''
            );

            setValue('editReason', rule.reason || '');

            $('editReasonCharCount').textContent =
                `${(rule.reason || '').length} / 40`;

            clearAllErrors();
            openModal(editModal);

        } catch (error) {
            console.error(error);
            sweetError('수정할 규칙을 불러오지 못했습니다.');
        }
    }

    // ============================================
    // 13. 예외 규칙 수정 저장
    // ============================================

    editForm?.addEventListener('submit', async e => {

        e.preventDefault();

        const ruleId = value('editRuleId');

        const startTime = value('editExceptionStartTime');
        const endTime = value('editExceptionEndTime');

        const validFrom = value('editValidFrom');
        const validTo = value('editValidTo') || null;

        const reason = value('editReason').trim();

        if (!ruleId || !startTime || !endTime || !reason) {
            sweetWarning('필수 항목을 모두 입력해주세요.');
            return;
        }

        if (startTime >= endTime) {
            sweetWarning('퇴근시간은 출근시간보다 늦어야 합니다.');
            return;
        }

        if (validTo && validTo < validFrom) {
            sweetWarning('종료일은 시작일보다 빠를 수 없습니다.');
            return;
        }

        const lunch = getLunchPayload(
            'editExceptionBreakStartTime',
            'editExceptionBreakEndTime',
            'editExceptionBreakMinutes',
            startTime,
            endTime,
            false
        );

        if (!lunch) return;

        const data = {
            startTime: toApiTime(startTime),
            endTime: toApiTime(endTime),
            ...lunch,
            reason,
            validFrom,
            validTo,
            isActive: true
        };

        try {
            const res = await apiFetch(
                `/api/admin/rules/exception/${ruleId}`,
                {
                    method: 'PUT',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(data)
                }
            );

            if (res.ok) {
                sweetSuccess('예외 규칙이 수정되었습니다.');
                closeModal(editModal);
                await loadExceptionRules();
            } else {
                await showApiError(
                    res,
                    '예외 규칙 수정에 실패했습니다.'
                );
            }

        } catch (error) {
            console.error(error);
            sweetError('통신 중 오류가 발생했습니다.');
        }
    });

    // ============================================
    // 14. 예외 규칙 삭제
    // ============================================

    async function deleteExceptionRule(ruleId) {

        const confirmation = await sweetConfirm(
            '삭제 확인',
            '정말 삭제하시겠습니까?'
        );

        if (!confirmation.isConfirmed) return;

        try {
            const res = await apiFetch(
                `/api/admin/rules/exception/${ruleId}`,
                {
                    method: 'DELETE'
                }
            );

            if (res.ok) {
                sweetSuccess('예외 규칙이 삭제되었습니다.');
                await loadExceptionRules();
            } else {
                await showApiError(
                    res,
                    '삭제에 실패했습니다.'
                );
            }

        } catch (error) {
            console.error(error);
            sweetError('통신 중 오류가 발생했습니다.');
        }
    }

    // ============================================
    // 15. 사원 검색
    // ============================================

    $('employeeSearchBtn')?.addEventListener('click', async () => {

        const keyword = value('employeeSearchInput').trim();

        if (!keyword) {
            sweetWarning('사원 이름 또는 사번을 입력해주세요.');
            return;
        }

        try {
            const res = await apiFetch(
                `/api/admin/employees?keyword=${encodeURIComponent(keyword)}&page=0&size=100`
            );

            if (!res.ok) {
                await showApiError(res, '사원 검색에 실패했습니다.');
                return;
            }

            const response = await res.json();

            displayEmployeeResults(
                response.data?.content || []
            );

        } catch (error) {
            console.error(error);
            sweetError('사원 검색 중 오류가 발생했습니다.');
        }
    });

    function displayEmployeeResults(employees) {

        const resultsDiv = $('employeeSearchResults');
        resultsDiv.innerHTML = '';

        const ul = document.createElement('ul');
        ul.className = 'employee-list';

        employees.forEach(emp => {

            const li = document.createElement('li');
            li.className = 'employee-item';

            // 사용자 입력 문자열을 HTML에 직접 삽입하지 않음
            const nameDiv = document.createElement('div');
            nameDiv.className = 'employee-name';
            nameDiv.textContent = emp.name || '';

            const detailsDiv = document.createElement('div');
            detailsDiv.className = 'employee-details';
            detailsDiv.textContent = emp.orgPath || '';

            li.appendChild(nameDiv);
            li.appendChild(detailsDiv);

            li.addEventListener('click', () => {

                setValue('selectedEmployeeId', emp.id);

                $('selectedEmployeeInfo').textContent =
                    `${emp.name} (${emp.orgPath || ''})`;

                $('selectedEmployeeInfo').style.display = 'block';

                resultsDiv.innerHTML = '';
            });

            ul.appendChild(li);
        });

        resultsDiv.appendChild(ul);
    }

    // ============================================
    // 16. 초기 데이터 로드
    // ============================================

    loadDefaultRule();
    loadLeaveTimePolicy();
    loadExceptionRules();

});