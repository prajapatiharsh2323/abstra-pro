"use client";

import { motion } from "framer-motion";
import { Download, Star, Terminal } from "lucide-react";
import Reveal from "./Reveal";
import { fadeUp, scaleIn, viewportOnce, RELEASES_URL, GITHUB_URL } from "@/lib/motion";

const FACTS = [
  "Android 7.0+",
  "Fully offline",
  "No account",
  "No network permission",
  "On-device AI",
];

export default function DownloadSection() {
  return (
    <section id="download" className="relative scroll-mt-24 overflow-hidden border-t border-[rgba(252,228,234,0.06)]">
      <div
        aria-hidden="true"
        className="absolute inset-0"
        style={{
          background:
            "radial-gradient(60% 80% at 50% 110%, rgba(229,129,158,0.14), transparent 70%)",
        }}
      />
      <div className="relative mx-auto max-w-3xl px-5 py-24 text-center md:px-8 md:py-32">
        <motion.div
          initial="hidden"
          whileInView="visible"
          viewport={viewportOnce}
          variants={scaleIn}
        >
          <h2 className="text-[clamp(2.2rem,5.4vw,3.6rem)] font-bold leading-[1.02]">
            Your music is waiting
            <br />
            right where you left it.
          </h2>
          <p className="mx-auto mt-5 max-w-md text-[16px] leading-relaxed">
            Abstra Pro is a separate app from Abstra, with its own release
            cadence. Built to run entirely on your device.
          </p>
        </motion.div>

        <Reveal variants={fadeUp} className="mt-10 flex flex-wrap items-center justify-center gap-3.5">
          <a href={RELEASES_URL} className="btn btn-primary text-[16px]" data-cta="download">
            <Download size={17} strokeWidth={2.2} /> Download APK
          </a>
          <a
            href={GITHUB_URL}
            className="btn btn-secondary text-[15px]"
            target="_blank"
            rel="noreferrer"
          >
            <Star size={15} /> Star on GitHub
          </a>
        </Reveal>

        <Reveal variants={fadeUp} className="mt-10">
          <ul className="mx-auto flex max-w-full flex-wrap items-center justify-center gap-x-5 gap-y-2 text-[12.5px] text-[#766080]">
            {FACTS.map((f) => (
              <li key={f}>{f}</li>
            ))}
          </ul>
          <p className="mt-6 flex items-center justify-center gap-1.5 text-[12.5px] text-[#766080]">
            <Terminal size={13} style={{ color: "#BC8390" }} />
            Store listings for Google Play and F-Droid are planned — grab the
            signed APK from GitHub Releases today.
          </p>
        </Reveal>
      </div>
    </section>
  );
}
