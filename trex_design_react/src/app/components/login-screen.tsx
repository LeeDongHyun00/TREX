import { useState } from "react";
import { ArrowLeft, Check, Mail, KeyRound, IdCard } from "lucide-react";
import dinoImg from "../../imports/01-Photoroom.png";

type View = "login" | "signup" | "find";

export function LoginScreen({ onLogin }: { onLogin: () => void }) {
  const [view, setView] = useState<View>("login");

  return (
    <div className="w-full h-full bg-[#1F2618] text-white flex flex-col">
      {view === "login" && (
        <LoginView onLogin={onLogin} go={(v) => setView(v)} />
      )}
      {view === "signup" && <SignupView back={() => setView("login")} onDone={onLogin} />}
      {view === "find" && <FindView back={() => setView("login")} />}
    </div>
  );
}

function Field({
  value,
  onChange,
  placeholder,
  type = "text",
  hint,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder: string;
  type?: string;
  hint?: string;
}) {
  return (
    <label className="block">
      <span className="text-[12px] text-white/80">{placeholder}</span>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1.5 w-full h-12 rounded-2xl bg-white/[0.08] border border-white/15 px-4 text-[14px] text-white placeholder:text-white/50 outline-none focus:border-[#C7E26B] transition-colors"
      />
      {hint && <span className="text-[11px] text-white/65 mt-1 block">{hint}</span>}
    </label>
  );
}

function LoginView({
  onLogin,
  go,
}: {
  onLogin: () => void;
  go: (v: View) => void;
}) {
  const [id, setId] = useState("");
  const [pw, setPw] = useState("");

  return (
    <div
      className="w-full h-full flex flex-col items-center px-7 relative"
      style={{
        backgroundImage: `url(${dinoImg})`,
        backgroundRepeat: "no-repeat",
        backgroundPosition: "center top",
        backgroundSize: "contain",
      }}
    >
      <div className="absolute inset-0 bg-gradient-to-b from-transparent via-[#1F2618]/40 to-[#1F2618]" />

      <div className="relative w-full flex flex-col items-center pt-[300px]">
        <p className="text-[11px] tracking-[0.4em] text-[#C7E26B]">TREX</p>
        <p className="text-white mt-1.5">바로 보고, 바로 고치고</p>
      </div>

      <form
        className="relative w-full mt-7 space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          onLogin();
        }}
      >
        <input
          value={id}
          onChange={(e) => setId(e.target.value)}
          placeholder="아이디"
          className="w-full h-12 rounded-2xl bg-white/10 backdrop-blur px-4 text-[14px] text-white placeholder:text-white/40 outline-none border border-white/10 focus:border-[#C7E26B]"
        />
        <input
          value={pw}
          onChange={(e) => setPw(e.target.value)}
          type="password"
          placeholder="비밀번호"
          className="w-full h-12 rounded-2xl bg-white/10 backdrop-blur px-4 text-[14px] text-white placeholder:text-white/40 outline-none border border-white/10 focus:border-[#C7E26B]"
        />
        <button
          type="submit"
          className="w-full h-12 rounded-2xl bg-[#C7E26B] text-[#1F2618] mt-2 active:bg-[#A8C47C]"
        >
          로그인
        </button>
      </form>

      <div className="relative mt-4 flex items-center gap-3 text-[11px] text-white/40 whitespace-nowrap">
        <button onClick={() => go("signup")} className="hover:text-white/70">
          회원가입
        </button>
        <span className="w-px h-3 bg-white/20" />
        <button onClick={() => go("find")} className="hover:text-white/70">
          아이디/비밀번호 찾기
        </button>
      </div>
    </div>
  );
}

function Header({ title }: { title: string }) {
  return (
    <div className="px-6 pt-12">
      <h2>{title}</h2>
    </div>
  );
}

function BackButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-[52px] h-[52px] rounded-2xl bg-white/[0.08] border border-white/15 text-white flex items-center justify-center shrink-0"
    >
      <ArrowLeft size={18} />
    </button>
  );
}

function SignupView({ back, onDone }: { back: () => void; onDone: () => void }) {
  const [name, setName] = useState("");
  const [id, setId] = useState("");
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [email, setEmail] = useState("");
  const [agree, setAgree] = useState(false);

  const valid =
    name && id.length >= 4 && pw.length >= 8 && pw === pw2 && email.includes("@") && agree;

  return (
    <div className="w-full h-full flex flex-col">
      <Header title="회원가입" />

      <div className="px-6 mt-2">
        <p className="text-[13px] text-white/75">
          TREX와 함께 시작할 준비가 되었어룡?
        </p>
      </div>

      <div className="px-6 mt-6 space-y-3.5 flex-1 overflow-y-auto pb-6">
        <Field value={name} onChange={setName} placeholder="이름" />
        <Field
          value={id}
          onChange={setId}
          placeholder="아이디"
          hint="4자 이상 영문/숫자"
        />
        <Field
          value={pw}
          onChange={setPw}
          placeholder="비밀번호"
          type="password"
          hint="8자 이상, 영문·숫자 조합"
        />
        <Field
          value={pw2}
          onChange={setPw2}
          placeholder="비밀번호 확인"
          type="password"
          hint={pw && pw2 && pw !== pw2 ? "비밀번호가 일치하지 않아요" : undefined}
        />
        <Field
          value={email}
          onChange={setEmail}
          placeholder="이메일"
          type="email"
          hint="아이디·비밀번호 찾기에 사용돼요"
        />

        <button
          onClick={() => setAgree(!agree)}
          className="w-full mt-2 flex items-center gap-3 px-4 py-3 rounded-2xl bg-white/[0.04] border border-white/10 text-left"
        >
          <div
            className={`w-5 h-5 rounded-md flex items-center justify-center shrink-0 ${
              agree ? "bg-[#C7E26B] text-[#1F2618]" : "border border-white/30"
            }`}
          >
            {agree && <Check size={13} />}
          </div>
          <span className="text-[13px] text-white/85 flex-1">
            서비스 약관 및 개인정보 처리방침에 동의합니다
          </span>
        </button>
      </div>

      <div className="px-6 pb-6 flex gap-2.5">
        <button
          disabled={!valid}
          onClick={onDone}
          className={`flex-1 rounded-2xl flex items-center justify-center transition-all ${
            valid ? "bg-[#C7E26B] text-[#1F2618]" : "bg-white/10 text-white/50"
          }`}
          style={{ height: 52 }}
        >
          가입 완료
        </button>
        <BackButton onClick={back} />
      </div>
    </div>
  );
}

function FindView({ back }: { back: () => void }) {
  const [mode, setMode] = useState<"id" | "pw">("id");
  const [email, setEmail] = useState("");
  const [id, setId] = useState("");
  const [sent, setSent] = useState(false);

  const valid =
    mode === "id" ? email.includes("@") : email.includes("@") && id.length > 0;

  return (
    <div className="w-full h-full flex flex-col">
      <Header title="아이디 / 비밀번호 찾기" />

      <div className="px-6 mt-5">
        <div className="rounded-2xl bg-white/[0.04] border border-white/10 p-1 grid grid-cols-2 gap-1">
          {[
            { id: "id" as const, label: "아이디 찾기", icon: IdCard },
            { id: "pw" as const, label: "비밀번호 찾기", icon: KeyRound },
          ].map((t) => {
            const Icon = t.icon;
            const active = mode === t.id;
            return (
              <button
                key={t.id}
                onClick={() => {
                  setMode(t.id);
                  setSent(false);
                }}
                className={`h-10 rounded-xl flex items-center justify-center gap-1.5 text-[12px] transition-colors ${
                  active ? "bg-[#C7E26B] text-[#1F2618]" : "text-white/80"
                }`}
              >
                <Icon size={13} /> {t.label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="px-6 mt-6 flex-1 overflow-y-auto pb-6">
        {sent ? (
          <div className="rounded-2xl bg-[#C7E26B] text-[#1F2618] p-5">
            <div className="w-9 h-9 rounded-full bg-[#1F2618] text-[#C7E26B] flex items-center justify-center">
              <Mail size={16} />
            </div>
            <p className="mt-3 text-[14px]">
              {mode === "id" ? "아이디를 이메일로 보내드렸어요" : "재설정 링크를 이메일로 보내드렸어요"}
            </p>
            <p className="text-[12px] opacity-80 mt-1">{email} 로 전송됨</p>
            <button
              onClick={back}
              className="mt-4 w-full h-11 rounded-xl bg-[#1F2618] text-[#C7E26B]"
            >
              로그인으로 돌아가기
            </button>
          </div>
        ) : (
          <div className="space-y-3.5">
            {mode === "pw" && (
              <Field
                value={id}
                onChange={setId}
                placeholder="아이디"
                hint="가입 시 사용한 아이디"
              />
            )}
            <Field
              value={email}
              onChange={setEmail}
              placeholder="이메일"
              type="email"
              hint="가입 시 등록한 이메일로 보내드려요"
            />
            <p className="text-[12px] text-white/70 leading-relaxed mt-2">
              {mode === "id"
                ? "입력한 이메일로 가입된 아이디를 안내해드려요."
                : "입력한 정보가 일치하면 비밀번호 재설정 링크를 보내드려요."}
            </p>
          </div>
        )}
      </div>

      {!sent && (
        <div className="px-6 pb-6 flex gap-2.5">
          <button
            disabled={!valid}
            onClick={() => setSent(true)}
            className={`flex-1 rounded-2xl flex items-center justify-center transition-all ${
              valid ? "bg-[#C7E26B] text-[#1F2618]" : "bg-white/10 text-white/50"
            }`}
            style={{ height: 52 }}
          >
            {mode === "id" ? "아이디 받기" : "재설정 링크 받기"}
          </button>
          <BackButton onClick={back} />
        </div>
      )}
    </div>
  );
}
