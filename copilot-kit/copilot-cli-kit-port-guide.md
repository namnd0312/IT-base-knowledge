# Kit cho GitHub Copilot CLI: thiết kế, cài đặt, kiểm thử

Bản chuyển phần lõi của ClaudeKit Engineer v2.20.0 sang GitHub Copilot CLI. Mã nằm ở `copilot-kit/.github/`. Kiến trúc của kit gốc: [kit-architecture-operation-and-maintenance-guide](../docs/kit-architecture-operation-and-maintenance-guide.md).

Phạm vi đã chốt: chỉ Copilot CLI, làm lõi trước (instructions, hooks, 10 skill workflow, 13 agents).

## Trạng thái kiểm chứng

| Hạng mục | Trạng thái |
|---|---|
| Hooks chạy đúng với payload giả lập | Đã chạy: 29 trên 29 test đạt (`node --test`, Node v24.1.0, 2026-11-19) |
| Định dạng file hook, tên sự kiện, trường stdout | Đối chiếu tài liệu GitHub ngày 2026-11-19 |
| Kit chạy trong Copilot CLI thật | Chưa chạy. Máy này chưa cài Copilot CLI |
| Tên trường tham số của từng tool (`toolArgs`) | Tài liệu không nêu. Guard dò theo dạng khóa, cần xác nhận bằng phiên thật |
| Vị trí file custom agent cho CLI (`.github/agents/*.agent.md`) | Theo quy ước chung của Copilot, trang tài liệu đã đọc không nêu riêng cho CLI |

Việc đầu tiên sau khi cài Copilot CLI là chạy mục "Kiểm thử trong Copilot CLI thật" bên dưới.

## Cấu trúc

```
copilot-kit/.github/
├── copilot-instructions.md          # hợp đồng luôn được nạp
├── hooks/copilot-kit.json           # đăng ký 3 hook
├── agents/<tên>.agent.md            # 13 custom agents
├── skills/kit-<tên>/SKILL.md        # 10 skill workflow
└── copilot-kit/
    ├── config.json                  # cấu hình kit
    ├── .kitignore                   # thư mục bị scout guard chặn
    ├── hooks/                       # 3 script hook + lib/
    ├── rules/                       # 5 file rule đọc khi cần
    ├── scripts/set-active-plan.cjs
    └── tests/hooks.test.cjs
```

Script và config đặt trong `.github/copilot-kit/` chứ không đặt trong `.github/hooks/`, vì Copilot nạp mọi file `*.json` trong `.github/hooks/` như file đăng ký hook.

## Ánh xạ từ kit gốc

| Kit gốc | Kit Copilot | Ghi chú |
|---|---|---|
| `rules/CLAUDE.md` | `.github/copilot-instructions.md` | Gộp thêm quy tắc đặt tên file và bảng workflow |
| 5 file rules còn lại | `.github/copilot-kit/rules/*.md` | Giữ thiết kế đọc khi cần |
| `settings.json` phần hooks | `.github/hooks/copilot-kit.json` | Sự kiện camelCase |
| `session-init` + `dev-rules-reminder` | `session-start-context.cjs` (`sessionStart`) | Bơm một lần đầu phiên |
| `subagent-init` | `subagent-start-context.cjs` (`subagentStart`) | |
| `scout-block` + `privacy-block` | `pre-tool-guard.cjs` (`preToolUse`) | Gộp một tiến trình vì hook chạy ở mọi tool call |
| `.ckignore` | `.github/copilot-kit/.kitignore` | |
| `.ck.json` | `.github/copilot-kit/config.json` | Chỉ giữ key có code đọc |
| `set-active-plan.cjs` | `.github/copilot-kit/scripts/set-active-plan.cjs` | Lưu vào `plans/.active-plan` |
| Skills `ck:*` | `kit-plan`, `kit-cook`, `kit-fix`, `kit-test`, `kit-code-review`, `kit-debug`, `kit-scout`, `kit-brainstorm`, `kit-docs`, `kit-git` | Tiền tố `kit-` để tránh trùng lệnh có sẵn của CLI |
| 13 agents | `.github/agents/<tên>.agent.md` | Bỏ `model`, bỏ Team Mode và memory |

## Khác biệt có chủ đích so với kit gốc

| Điểm | Kit gốc | Kit Copilot | Lý do |
|---|---|---|---|
| Nhắc rule | Mỗi prompt | Một lần đầu phiên, phần còn lại nằm trong instructions | `userPromptSubmitted` của Copilot không bơm được ngữ cảnh |
| File nhạy cảm | Chặn rồi để model tự khai `APPROVED:` | Trả `permissionDecision: "ask"`, Copilot hỏi thẳng user | Người quyết định là user, không phải model |
| Lệnh shell đụng file nhạy cảm | Chỉ cảnh báo, chỉ nhận `.env*` | Hỏi user, nhận mọi mẫu (`.pem`, `.key`, credentials...) | Vá lỗ hổng của bản gốc |
| Scout guard với shell | So chuỗi trên cả lệnh | Chỉ xét lệnh đọc hoặc liệt kê (`ls`, `cat`, `find`, `grep`...), bỏ qua đối số loại trừ và mẫu tìm kiếm | Bản gốc chặn nhầm `find -not -path '*/node_modules/*'` |
| Gốc đường dẫn | cwd của tiến trình | Vị trí file của kit | Bản gốc tính sai đường dẫn khi chạy ở thư mục con |
| Active plan | File tạm theo phiên, bị xóa ở mỗi SessionStart | `plans/.active-plan`, giữ qua các phiên | Bản gốc làm mất plan sau resume |
| Khi guard crash | Exit 0 | Exit 0 kèm `{}` | Copilot coi mã thoát khác 0 của `preToolUse` là từ chối, guard hỏng sẽ khóa mọi tool |

## Thay đổi hành vi trong skill và agent

Skill và agent được viết lại rút gọn (10 `SKILL.md` + 9 file reference, 2041 dòng; 13 agent, mỗi file 46 đến 105 dòng). Những chỗ hành vi khác bản gốc:

| Nơi | Thay đổi |
|---|---|
| `kit-cook --auto` | Bản gốc dựa vào artifact gate. Bản này tự định nghĩa việc rủi ro cao phải dừng hỏi user: auth, thanh toán, migration, xóa dữ liệu, đổi contract công khai, bảo mật, hạ tầng. Danh sách này là mới, nên duyệt lại |
| `kit-plan` | Bỏ `archive`, `--html`, `--github`, `--wiki`, `--global`. File plan viết thẳng từ mẫu thay vì lệnh `ck plan create`. Giữ các mode fast, hard, deep, parallel, two, `--tdd` |
| `kit-fix` | Bảy file workflow theo loại lỗi gộp thành một bảng. Chỉ bắt buộc giao `code-reviewer` và `tester` với lỗi vừa và phức tạp |
| `kit-git merge-pr` | Review bằng `/kit-code-review` thay cho skill `review-pr` chưa chuyển, nên không tự sửa nhánh PR và không trả lời review. Hỏi user trước khi merge |
| `kit-debug`, `kit-docs` | Script đi kèm (bisect test, validate docs) thay bằng quy trình làm tay |
| `kit-scout` | Bỏ chế độ gọi CLI ngoài. Dùng tối đa 6 subagent chỉ đọc |
| `debugger` | Chỉ sửa khi được yêu cầu, còn lại đề xuất và chuyển sang `/kit-fix`. Chặt hơn bản gốc |
| `tester` | Chỉ tạo hoặc sửa file test, lỗi ở mã sản phẩm thì báo cáo |
| `project-manager`, `git-manager` | Bản gốc chỉ ghi "kích hoạt skill X". Phần thân được viết mới, nên duyệt kỹ hơn các agent khác |
| `brainstormer`, `code-reviewer`, `researcher` | Không có quyền `edit`. Nếu không ghi được report qua shell thì trả nguyên report trong câu trả lời |
| `ui-ux-designer` | Bỏ các bước phụ thuộc skill chưa chuyển (`ui-ux-pro-max`, tạo ảnh AI, Figma) |
| Mọi agent | Dùng chung một bộ trạng thái `DONE / DONE_WITH_CONCERNS / BLOCKED / NEEDS_CONTEXT`. Bỏ Team Mode, memory, trường `model` |

## Chưa chuyển

| Thành phần | Lý do |
|---|---|
| Statusline, hook quota | Không tìm thấy cơ chế tương đương; hook quota gắn với OAuth của Claude |
| `session-state` (ghi `latest.md`, đọc transcript) | Phụ thuộc định dạng transcript của Claude. Có thể viết lại bằng `agentStop` sau |
| `descriptive-name` | `preToolUse` không bơm được ngữ cảnh; nội dung đã đưa vào instructions |
| `simplify-gate`, `workflow-artifact-gate`, notifications | Tắt mặc định ở bản gốc |
| Chặn glob quá rộng của `scout-block` | Chưa cần ở đợt lõi |
| `codingLevel` và 6 output style | Chưa cần ở đợt lõi |
| 78 skill còn lại | Đợt sau. Skill chuyên ngành ít phụ thuộc Claude nên chuyển nhanh hơn skill workflow |
| CLI `ck`, `metadata.json` | Cài bằng cách chép thư mục |

## Cài vào một project

```bash
cp -R /đường/dẫn/copilot-kit/.github /đường/dẫn/project/
```

Lệnh này gộp vào `.github/` có sẵn. Kiểm tra trước nếu project đã có `copilot-instructions.md` riêng, vì file đó sẽ bị ghi đè.

Yêu cầu: Node.js (hooks chỉ dùng module có sẵn, không cần `npm install`) và GitHub Copilot CLI.

Cấu hình trong `.github/copilot-kit/config.json`:

| Key | Mặc định | Tác dụng |
|---|---|---|
| `paths.plans`, `paths.docs` | `plans`, `docs` | Đường dẫn bơm vào ngữ cảnh. Giá trị thoát khỏi project sẽ bị thay bằng mặc định |
| `plan.reportsDir` | `reports` | Tên thư mục report |
| `guards.scout`, `guards.privacy` | true | false để tắt từng guard |
| `locale.responseLanguage` | null | Ví dụ `"Vietnamese"` |
| `assertions` | [] | Các câu khẳng định in ra đầu phiên |

Cho phép lại một thư mục bị chặn: thêm `!tên-thư-mục` vào `.github/copilot-kit/.kitignore`.

## Cách dùng

Các bước dưới đây là cách dùng theo thiết kế, chưa được chạy trong Copilot CLI thật.

### Mở phiên

Chạy `copilot` ở thư mục gốc của project. Đầu phiên, hook tự bơm đường dẫn plan, report và quy tắc đặt tên. Kiểm tra nhanh kit đã được nhận:

- `/skills list` phải thấy 10 skill `kit-*`.
- `/agent` phải thấy 13 agent.
- Hỏi "đường dẫn Plans là gì?", Copilot phải trả lời đúng `<project>/plans`.

### Lệnh hằng ngày

Gõ tên skill kèm yêu cầu, hoặc mô tả việc cần làm và để Copilot tự chọn skill theo mô tả.

| Bạn muốn | Gõ |
|---|---|
| Làm tính năng mới | `/kit-plan thêm đăng nhập bằng Google`, duyệt plan, rồi `/kit-cook <đường dẫn plan.md>` |
| Việc nhỏ, không cần nghiên cứu | `/kit-cook --fast đổi màu nút submit` |
| Sửa bug hoặc CI hỏng | `/kit-fix <mô tả lỗi hoặc log>` |
| Tìm hiểu codebase | `/kit-scout luồng thanh toán nằm ở đâu` |
| Tìm nguyên nhân gốc, chưa sửa | `/kit-debug <triệu chứng>` |
| Cân nhắc phương án | `/kit-brainstorm REST hay GraphQL cho API này` |
| Chạy test | `/kit-test` |
| Review trước khi merge | `/kit-code-review` |
| Cập nhật tài liệu | `/kit-docs` |
| Commit | `/kit-git cm` |

Cờ của `kit-cook`:

| Cờ | Tác dụng |
|---|---|
| `--fast` | Bỏ bước nghiên cứu, vẫn lập plan |
| `--parallel` | Nhiều agent `fullstack-developer` cùng làm, mỗi agent sở hữu file riêng |
| `--auto` | Bỏ bước hỏi duyệt với việc rủi ro thấp |
| `--no-test` | Bỏ bước test, có cảnh báo khi finalize |
| `--tdd` | Viết test cho hành vi hiện tại trước, kết hợp được với mọi mode |

### Hành vi cần biết trước

- `/kit-plan` chỉ viết plan vào `plans/<thời gian>-<tên>/` rồi dừng lại hỏi; nó không viết code.
- `/kit-cook` dừng hỏi duyệt sau mỗi giai đoạn, luôn giao review cho agent `code-reviewer`, và luôn hỏi trước khi commit.
- Đọc `.env`, `*.pem`, `*.key`, `*credentials*` sẽ hiện hộp thoại xin quyền.
- Đọc `node_modules`, `dist`, `build`, `.git` sẽ bị từ chối.
- Sửa `config.json` hoặc file instructions có hiệu lực từ phiên mới.

### Khi có gì không đúng

| Triệu chứng | Xử lý |
|---|---|
| Guard chặn sai một thư mục | Thêm `!tên-thư-mục` vào `.github/copilot-kit/.kitignore` |
| Guard không chặn gì | Xem `.github/copilot-kit/.logs/hook-log.jsonl`. Log trống nghĩa là tên trường trong `toolArgs` khác dự đoán: chỉnh `PATH_KEY` trong `.github/copilot-kit/hooks/lib/tool-path-checks.cjs` |
| Copilot không biết đường dẫn plan | Hook `sessionStart` không chạy: kiểm tra `.github/hooks/copilot-kit.json` và `node` có trong PATH |
| Subagent ghi report sai chỗ | Hook `subagentStart` không chạy cho agent đó; xem câu hỏi mở bên dưới |
| Plan đang làm không được nhận | Chạy `node .github/copilot-kit/scripts/set-active-plan.cjs <thư mục plan>` |

## Kiểm thử

Test hook (không cần Copilot):

```bash
cd copilot-kit && node --test .github/copilot-kit/tests/hooks.test.cjs
```

Chạy tay một hook:

```bash
echo '{"toolName":"view","toolArgs":{"path":".env"}}' | node .github/copilot-kit/hooks/pre-tool-guard.cjs
```

### Kiểm thử trong Copilot CLI thật

Chưa làm được trên máy này. Thứ tự đề xuất, mỗi bước xác nhận một giả định:

1. Mở Copilot CLI ở gốc project đã cài kit. Hỏi "đường dẫn Plans và mẫu Naming là gì?". Trả lời đúng nghĩa là `sessionStart` đã bơm ngữ cảnh.
2. Yêu cầu đọc một file trong `node_modules`. Phải bị từ chối kèm lý do của scout guard.
3. Yêu cầu đọc `.env`. Phải hiện hộp thoại xin quyền.
4. Xem `.github/copilot-kit/.logs/hook-log.jsonl`. Mỗi lần deny, ask hoặc crash có một dòng. Nếu bước 2 hoặc 3 không chặn mà log trống thì tên trường trong `toolArgs` khác dự đoán: chỉnh `PATH_KEY` trong `.github/copilot-kit/hooks/lib/tool-path-checks.cjs`.
5. Gõ `/skills list` và `/agent` để xác nhận 10 skill và 13 agent được nhận.
6. Chạy `/kit-plan` cho một việc nhỏ, rồi kiểm tra subagent `planner` có nhận khối `## Paths` không (báo cáo của nó phải nằm đúng thư mục Reports).

## So sánh với ClaudeKit

Kit Copilot là bản lõi rút gọn: giữ mô hình (hook bơm ngữ cảnh, guard, skill workflow, agent chuyên trách), bỏ phần lớn độ phủ và tự động hóa.

### Quy mô

Số dòng đo ngày 2026-11-19 bằng `wc -l`.

| | ClaudeKit v2.20.0 | Kit Copilot |
|---|---|---|
| Số file | 1512 | 50 |
| Skills | 88 | 10 (chỉ nhóm workflow) |
| Agents | 13 | 13 |
| Hook đăng ký | 11 | 3 |
| Mã hook và statusline | 8908 dòng | 572 dòng |
| 10 skill workflow (SKILL.md và references) | 9655 dòng | 2041 dòng |
| Agents | 1714 dòng | 1068 dòng |
| Test hook | 675 test, 666 đạt ở bản cài | 29 test, 29 đạt |

### Năng lực

| Năng lực | ClaudeKit | Kit Copilot |
|---|---|---|
| Bơm ngữ cảnh đầu phiên | Có | Có |
| Nhắc rule mỗi prompt | Có, 5 phút một lần | Không, Copilot không hỗ trợ |
| Ngữ cảnh cho subagent | Có | Có |
| Chặn thư mục nặng | Có, hay chặn nhầm lệnh loại trừ | Có, ít chặn nhầm hơn; chưa chặn glob quá rộng |
| File nhạy cảm | Model tự khai `APPROVED:`, shell không bị chặn | Copilot hỏi thẳng user, áp dụng cả shell |
| Active plan | Mất sau mỗi SessionStart | Giữ qua các phiên |
| Đường dẫn plan và report | Theo cwd, sai khi chạy ở thư mục con | Theo vị trí kit |
| Statusline, quota | Có | Không |
| Lưu trạng thái phiên | Có | Không |
| Thông báo Telegram, Discord, Slack | Có, tùy chọn | Không |
| Coding level, output style | Có | Không |
| Model riêng cho từng agent | Có | Không, dùng model mặc định |
| Bộ nhớ agent, agent teams | Có | Không |
| CLI plan, dashboard kanban | Có | Không, plan viết theo mẫu |
| Skill chuyên ngành | 78 | 0 |
| Trình cài, manifest checksum | Có | Không, chép thư mục |

### Độ tin cậy

| | ClaudeKit | Kit Copilot |
|---|---|---|
| Đã chạy thật | Có | Chưa |
| Nợ kỹ thuật | 62 điểm lệch và lỗi đã ghi nhận trong [known-issues](../docs/known-issues-and-inconsistencies.md) | Code nhỏ, mới viết, chưa qua sử dụng |
| Cổng `--auto` | Kiểm bằng code (artifact gate) | Danh sách rủi ro trong prompt, model tự tuân thủ |
| Rủi ro lớn nhất | Hành vi phụ thuộc cwd, cài trùng toàn cục và project | Hai giả định chưa xác nhận: tên trường tham số tool và vị trí file agent |

Trong Claude Code, ClaudeKit hơn hẳn về độ phủ. Kit Copilot dành cho trường hợp buộc dùng Copilot CLI, và hiện đủ cho vòng plan, code, test, review.

## Giấy phép

Nội dung skill và agent được viết lại từ một sản phẩm thương mại. Dùng nội bộ khác với phân phối lại; trước khi chia sẻ hoặc bán kit này cần xem điều khoản giấy phép của ClaudeKit.

## Câu hỏi còn mở

- Tên trường tham số thật của `view`, `edit`, `create`, `glob`, `grep`, `bash` trong payload `preToolUse`.
- `permissionDecision: "ask"` hoạt động thế nào khi Copilot CLI chạy không tương tác.
- `subagentStart` có bắn cho agent dựng sẵn không (tài liệu ghi agent general-purpose không phát sự kiện này).
- Tên agent chỉ đọc dựng sẵn của Copilot CLI: `kit-scout` đang gọi nó là "explore".
- Copilot CLI có nhận `tools` viết dạng mảng một dòng (`["search", "read"]`) trong frontmatter agent không; tài liệu cho phép cả mảng lẫn chuỗi ngăn bằng dấu phẩy.
- Thư mục làm việc mặc định của lệnh hook; file đăng ký đã đặt `"cwd": "."` để không phụ thuộc mặc định.

## Nguồn tài liệu

- [Hooks reference (GitHub Docs)](https://docs.github.com/copilot/reference/hooks-reference)
- [Copilot CLI hooks reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-hooks-reference)
- [Custom agents configuration](https://docs.github.com/en/copilot/reference/custom-agents-configuration)
- [Create skills for Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/create-skills)
- [Add custom instructions for Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-custom-instructions)
