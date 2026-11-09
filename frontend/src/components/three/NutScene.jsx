import { useEffect, useMemo, useRef } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import * as THREE from 'three';
import gsap from 'gsap';

const ROSE = '#ff0000';
const ROSE_LIGHT = '#ffffff';
const ROSE_DEEP = '#000000';

/** The SUTRA mark in 3D: an extruded hex nut with a threaded bore. */
function HexNut() {
  const group = useRef();
  const entrance = useRef();
  const geometry = useMemo(() => {
    const shape = new THREE.Shape();
    for (let i = 0; i < 6; i += 1) {
      const a = (Math.PI / 3) * i;
      const x = Math.cos(a) * 1.25;
      const y = Math.sin(a) * 1.25;
      if (i === 0) shape.moveTo(x, y); else shape.lineTo(x, y);
    }
    shape.closePath();
    const hole = new THREE.Path();
    hole.absarc(0, 0, 0.58, 0, Math.PI * 2, true);
    shape.holes.push(hole);
    const g = new THREE.ExtrudeGeometry(shape, {
      depth: 0.55, bevelEnabled: true, bevelThickness: 0.08, bevelSize: 0.07, bevelSegments: 4, curveSegments: 48,
    });
    g.center();
    return g;
  }, []);

  useEffect(() => {
    // fromTo (not from) so React's double-mount in development replays the same entrance
    const tl = gsap.timeline();
    tl.fromTo(entrance.current.scale, { x: 0.001, y: 0.001, z: 0.001 },
      { x: 1, y: 1, z: 1, duration: 1.4, ease: 'elastic.out(1, 0.55)' })
      .fromTo(entrance.current.rotation, { y: -Math.PI * 2 }, { y: 0, duration: 1.6, ease: 'power3.out' }, 0);
    return () => tl.kill();
  }, []);

  useFrame((state, delta) => {
    const g = group.current;
    g.rotation.z += delta * 0.25;
    g.rotation.x = THREE.MathUtils.lerp(g.rotation.x, 0.45 + state.pointer.y * 0.35, 0.06);
    g.rotation.y = THREE.MathUtils.lerp(g.rotation.y, -0.35 + state.pointer.x * 0.5, 0.06);
    g.position.y = Math.sin(state.clock.elapsedTime * 1.1) * 0.08;
  });

  return (
    <group ref={entrance}>
      <group ref={group}>
        <mesh geometry={geometry} castShadow>
          {/* Standard (not physical) material: its shader compiles far faster, which keeps the first frame from freezing the page */}
          <meshStandardMaterial color={ROSE} roughness={0.25} metalness={0.2} />
        </mesh>
        {[0, 1, 2, 3].map((i) => (
          <mesh key={i} position={[0, 0, -0.24 + i * 0.16]}>
            <torusGeometry args={[0.6, 0.025, 12, 64]} />
            <meshStandardMaterial color="#ffffff" roughness={0.35} />
          </mesh>
        ))}
      </group>
    </group>
  );
}

/** Vendors orbiting the buyer: small beads on tilted rings. */
function Orbit({ radius, tilt, speed, count, color }) {
  const ref = useRef();
  useFrame((_, delta) => { ref.current.rotation.z += delta * speed; });
  return (
    <group rotation={tilt}>
      <mesh>
        <torusGeometry args={[radius, 0.006, 8, 160]} />
        <meshBasicMaterial color={ROSE_LIGHT} transparent opacity={0.9} />
      </mesh>
      <group ref={ref}>
        {Array.from({ length: count }, (_, i) => {
          const a = (i / count) * Math.PI * 2;
          return (
            <mesh key={i} position={[Math.cos(a) * radius, Math.sin(a) * radius, 0]}>
              <sphereGeometry args={[0.085, 16, 16]} />
              <meshStandardMaterial color={i % 2 ? '#ffffff' : color} roughness={0.3} />
            </mesh>
          );
        })}
      </group>
    </group>
  );
}

function Floaters() {
  const items = useMemo(() => Array.from({ length: 9 }, (_, i) => ({
    position: [Math.sin(i * 2.1) * 3.4, Math.cos(i * 1.7) * 1.9, -1.5 - (i % 3) * 0.6],
    scale: 0.1 + (i % 4) * 0.05,
    speed: 0.3 + (i % 5) * 0.12,
  })), []);
  const ref = useRef();
  useFrame((state) => {
    ref.current.children.forEach((m, i) => {
      m.rotation.x += 0.004 + i * 0.0006;
      m.rotation.y += 0.006;
      m.position.y = items[i].position[1] + Math.sin(state.clock.elapsedTime * items[i].speed + i) * 0.2;
    });
  });
  return (
    <group ref={ref}>
      {items.map((it, i) => (
        <mesh key={i} position={it.position} scale={it.scale}>
          {i % 2 ? <octahedronGeometry args={[1, 0]} /> : <icosahedronGeometry args={[1, 0]} />}
          <meshStandardMaterial color={i % 3 ? ROSE_LIGHT : ROSE} roughness={0.4} flatShading />
        </mesh>
      ))}
    </group>
  );
}

export default function NutScene({ compact = false }) {
  return (
    <Canvas dpr={[1, 1.5]} camera={{ position: [0, 0, compact ? 5.4 : 6], fov: 42 }}
            gl={{ antialias: true, alpha: true, powerPreference: 'high-performance' }}>
      <ambientLight intensity={0.75} />
      <directionalLight position={[3, 4, 5]} intensity={1.6} />
      <pointLight position={[-4, -2, 3]} intensity={18} color={ROSE_DEEP} />
      <pointLight position={[2, 3, -2]} intensity={10} color="#ffffff" />
      <HexNut />
      <Orbit radius={2.0} tilt={[1.15, 0.2, 0]} speed={0.35} count={6} color={ROSE} />
      <Orbit radius={2.5} tilt={[1.3, -0.45, 0.3]} speed={-0.22} count={4} color={ROSE_DEEP} />
      {!compact && <Floaters />}
    </Canvas>
  );
}
