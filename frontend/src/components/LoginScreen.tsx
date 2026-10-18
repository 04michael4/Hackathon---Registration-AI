"use client";

import Image from "next/image";
import { useSyllo } from "@/context/SylloContext";

export function LoginScreen() {
  const { navigate, update } = useSyllo();
  const enter = (role: "student" | "advisor") => {
    update(`Enter ${role} demo`, (state) => { state.role = role; });
    navigate(role === "advisor" ? "advisor" : "features");
  };

  return (
    <section className="screen login-screen active react-screen">
      <div className="login-shell">
        <div className="login-brand" aria-label="Syllo by Lehigh University"><strong>syllo.</strong><span>LEHIGH UNIVERSITY</span></div>
        <div className="login-card">
          <Image className="login-crest" src="/assets/lehigh-crest.png" alt="Lehigh University crest" width={140} height={140} priority />
          <span className="login-kicker">COURSE PLANNING, CONNECTED</span>
          <h1>Welcome to Syllo.</h1>
          <p>Less planning. More possibilities.</p>
          <button className="button primary large login-button" onClick={() => enter("student")}><span>Sign in with Lehigh SSO</span><b>→</b></button>
          <button className="text-button" onClick={() => enter("advisor")}>Try advisor demo</button>
          <small className="sso-demo-note">Demo entry · SSO is not connected</small>
        </div>
      </div>
    </section>
  );
}
